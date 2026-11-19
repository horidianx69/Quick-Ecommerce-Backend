package com.quickcommerce.order;

import com.quickcommerce.order.OrderViews.CustomerOrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            @Size(max = 500) String note) {}

    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** 201 for a new order, 200 when the same idempotencyKey was already used (the first order is returned). */
    @PostMapping
    ResponseEntity<CustomerOrderView> place(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PlaceRequest req) {
        Map<Long, Integer> quantities = new HashMap<>();
        for (ItemRequest item : req.items()) {
            if (quantities.put(item.shopProductId(), item.quantity()) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each product can appear once in items");
            }
        }
        var placed = orderService.place(customerId(jwt), req.shopId(), req.idempotencyKey(), quantities,
                req.address(), req.note());
        return ResponseEntity.status(placed.created() ? HttpStatus.CREATED : HttpStatus.OK).body(placed.order());
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
