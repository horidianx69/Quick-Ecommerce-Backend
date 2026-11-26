package com.quickcommerce.payment;

import com.quickcommerce.order.OrderService;
import com.quickcommerce.payment.OrderPayments.Kind;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
class PaymentController {

    private static final int MAX_EVENT_BYTES = 256 * 1024;
    private static final long SIGNATURE_TOLERANCE_SECONDS = 300;
    private static final Pattern TIMESTAMP = Pattern.compile("(?:^|,)t=([^,]*)(?=,|$)");
    private static final Set<String> SESSION_EVENTS = Set.of("checkout.session.completed", "checkout.session.expired");

    record TipCheckout(@NotNull @Min(1) Integer tipCents) {}

    private final OrderPayments payments;
    private final OrderService orders;
    private final String signingSecret;
    private final boolean live;
    private final JsonMapper json;

    PaymentController(OrderPayments payments, OrderService orders, JsonMapper json, @Value("${app.payments.webhook-secret}") String signingSecret,
            @Value("${app.payments.live}") boolean live) {
        this.payments = payments;
        this.orders = orders;
        this.json = json;
        this.signingSecret = signingSecret;
        this.live = live;
        if (payments.enabled() && (!signingSecret.startsWith("whsec_") || signingSecret.length() <= 6)) {
            throw new IllegalStateException("Stripe webhook signing secret is required");
        }
    }

    @GetMapping("/api/orders/{id}/payment")
    OrderPayments.CustomerPayment customer(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return payments.customer(id, Long.parseLong(jwt.getSubject()), Kind.ORDER);
    }

    @GetMapping("/api/admin/orders/{id}/payment")
    OrderPayments.AdminPayment admin(@PathVariable long id) {
        return payments.admin(id, Kind.ORDER);
    }

    @PostMapping("/api/orders/{id}/tip/checkout")
    @ResponseStatus(HttpStatus.ACCEPTED)
    OrderPayments.CustomerPayment checkoutTip(@PathVariable long id, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody TipCheckout tip) {
        return orders.checkoutTip(Long.parseLong(jwt.getSubject()), id, tip.tipCents());
    }

    @GetMapping("/api/orders/{id}/tip/checkout")
    OrderPayments.CustomerPayment customerTip(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return payments.customer(id, Long.parseLong(jwt.getSubject()), Kind.TIP);
    }

    @GetMapping("/api/admin/orders/{id}/payment/tip")
    OrderPayments.AdminPayment adminTip(@PathVariable long id) {
        return payments.admin(id, Kind.TIP);
    }

    @GetMapping("/api/admin/stripe/payment-reviews")
    List<OrderPayments.PaymentReview> paymentReviews(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return payments.needsReview(page, size);
    }

    @PostMapping(path = "/api/payments/stripe/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void webhook(HttpServletRequest request, @RequestHeader("Stripe-Signature") String signature) throws IOException {
        if (!payments.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (request.getContentLengthLong() > MAX_EVENT_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
        }
        byte[] body = request.getInputStream().readNBytes(MAX_EVENT_BYTES + 1);
        if (body.length > MAX_EVENT_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
        }
        try {
            long now = Instant.now().getEpochSecond();
            var timestamp = TIMESTAMP.matcher(signature);
            if (!timestamp.find()) {
                throw invalidEvent();
            }
            long signedAt = Long.parseLong(timestamp.group(1));
            if (signedAt < now - SIGNATURE_TOLERANCE_SECONDS || signedAt > now + SIGNATURE_TOLERANCE_SECONDS
                    || timestamp.find()) {
                throw invalidEvent();
            }
            String payload = new String(body, StandardCharsets.UTF_8);
            Webhook.Signature.verifyHeader(payload, signature, signingSecret, SIGNATURE_TOLERANCE_SECONDS);
            var event = json.readTree(payload);
            if (event == null || !event.isObject()) {
                throw invalidEvent();
            }
            var mode = event.path("livemode");
            if (!mode.isBoolean() || mode.asBoolean() != live) {
                throw invalidEvent();
            }
            String type = event.path("type").asString("");
            if (SESSION_EVENTS.contains(type)) {
                String session = event.path("data").path("object").path("id").asString("");
                if (session.length() > 255 || !session.startsWith(live ? "cs_live_" : "cs_test_")) {
                    throw invalidEvent();
                }
                Instant completedAt = null;
                if ("checkout.session.completed".equals(type)) {
                    var created = event.path("created");
                    long completion = created.asLong(-1);
                    if (!created.isIntegralNumber() || completion <= 0 || completion > signedAt + SIGNATURE_TOLERANCE_SECONDS) {
                        throw invalidEvent();
                    }
                    completedAt = Instant.ofEpochSecond(completion);
                }
                payments.wake(session, live, completedAt);
            }
        } catch (SignatureVerificationException | JacksonException | IllegalArgumentException | IndexOutOfBoundsException e) {
            throw invalidEvent();
        }
    }

    private static ResponseStatusException invalidEvent() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Stripe event");
    }
}
