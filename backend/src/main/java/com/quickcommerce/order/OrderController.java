package com.quickcommerce.order;

import com.quickcommerce.auth.Throttle;
import com.quickcommerce.common.Bulkhead;
import com.quickcommerce.order.OrderViews.CustomerOrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The customer's side of orders. The customer id always comes from the token. */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public record ItemRequest(
            @NotNull Long shopProductId,
            @NotNull @Min(1) @Max(99) Integer quantity) {}

    /**
     * idempotencyKey: a random id (a UUID) the app makes once per checkout
     * attempt and sends again on every retry of that same attempt.
     * No prices are sent: the backend works them out.
     */
    public record PlaceRequest(
            @NotNull Long shopId,
            @NotBlank @Size(min = 8, max = 64) String idempotencyKey,
            @NotEmpty @Size(max = 50) List<@Valid @NotNull ItemRequest> items,
            @NotNull @Valid DeliveryAddress address,
            @Size(max = 500) String note,
            @Min(0) Integer tipCents) {}

    /** Same as placing, without the idempotency key: what would this cost right now? */
    public record QuoteRequest(
            @NotNull Long shopId,
            @NotEmpty @Size(max = 50) List<@Valid @NotNull ItemRequest> items,
            @NotNull @Valid DeliveryAddress address,
            @Min(0) Integer tipCents) {}

    /** One of the tip options from the quote's tipOptionsCents. */
    public record TipRequest(@NotNull @Min(1) Integer tipCents) {}

    public record ReceiptRequest(@NotNull Boolean received) {}

    private final OrderService orderService;

    private final Throttle throttle;
    private final Bulkhead checkouts;
    private final int checkoutsPerMinute;
    private final int quotesPerMinute;

    OrderController(OrderService orderService, Throttle throttle,
            @Value("${app.orders.checkouts-per-minute}") int checkoutsPerMinute,
            @Value("${app.orders.quotes-per-minute}") int quotesPerMinute,
            @Value("${app.orders.max-concurrent-checkouts}") int maxConcurrentCheckouts,
            @Value("${spring.datasource.hikari.maximum-pool-size}") int dbPoolSize) {
        this.orderService = orderService;
        this.throttle = throttle;
        this.checkoutsPerMinute = checkoutsPerMinute;
        this.quotesPerMinute = quotesPerMinute;
        if (maxConcurrentCheckouts < 1 || dbPoolSize < 2) {
            throw new IllegalArgumentException("Checkout needs at least one slot and a database pool of at least two connections");
        }
        this.checkouts = new Bulkhead(Math.min(maxConcurrentCheckouts, dbPoolSize - 1), 200,
                "Checkout is busy. Try again in a moment.");
    }

    private void limit(String kind, Long customerId, int perMinute, String message) {
        if (!throttle.tryAcquire(kind + ":" + customerId, perMinute, Duration.ofMinutes(1), Instant.now())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
        }
    }

    /** 201 for a new order, 200 when the same idempotencyKey was already used (the first order is returned). */
    @PostMapping
    ResponseEntity<CustomerOrderView> place(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PlaceRequest req) {
        Long customerId = customerId(jwt);
        limit("checkout", customerId, checkoutsPerMinute, "Too many checkouts. Wait a minute and try again.");
        Map<Long, Integer> quantities = quantities(req.items());
        var placed = checkouts.run(() -> orderService.place(customerId, req.shopId(), req.idempotencyKey(), quantities,
                req.address(), req.note(), req.tipCents() == null ? 0 : req.tipCents()));
        return ResponseEntity.status(placed.created() ? HttpStatus.CREATED : HttpStatus.OK).body(placed.order());
    }

    /**
     * Every fee and the total for this cart, without ordering. The app shows this
     * at checkout, so the customer sees the delivery, handling, small-cart,
     * late-night and surge fees before tapping "Place order".
     */
    @PostMapping("/quote")
    OrderService.Quote quote(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody QuoteRequest req) {
        limit("quote", customerId(jwt), quotesPerMinute, "Too many price checks. Wait a minute and try again.");
        Map<Long, Integer> quantities = quantities(req.items());
        return checkouts.run(() -> orderService.quote(req.shopId(), quantities, req.address(),
                req.tipCents() == null ? 0 : req.tipCents()));
    }

    /** Adds a tip after checkout, if none was given: until 24 h after delivery. */
    @PostMapping("/{id}/tip")
    CustomerOrderView tip(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody TipRequest req) {
        return orderService.tip(customerId(jwt), id, req.tipCents());
    }

    /**
     * "Did you receive your order?". Answer when receiptStatus is ASKED, or report
     * "not received" (received=false) within 24 h of delivery.
     */
    @PostMapping("/{id}/receipt")
    CustomerOrderView receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody ReceiptRequest req) {
        return orderService.answerReceipt(customerId(jwt), id, req.received());
    }

    private static Map<Long, Integer> quantities(List<ItemRequest> items) {
        Map<Long, Integer> quantities = new HashMap<>();
        for (ItemRequest item : items) {
            if (quantities.put(item.shopProductId(), item.quantity()) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each product can appear once in items");
            }
        }
        return quantities;
    }

    /** The caller's own orders, newest first. */
    @GetMapping
    Page<CustomerOrderView> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return orderService.listForCustomer(customerId(jwt), page, size);
    }

    @GetMapping("/{id}")
    CustomerOrderView get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return orderService.getForCustomer(customerId(jwt), id);
    }

    /** Works only while the shop has not answered (status PLACED); otherwise 409. */
    @PostMapping("/{id}/cancel")
    CustomerOrderView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return orderService.cancel(customerId(jwt), id);
    }

    private static Long customerId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
