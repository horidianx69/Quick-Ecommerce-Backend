package com.quickcommerce.payment;

import com.quickcommerce.common.Paging;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class OrderPayments {

    public enum State { OPENING, OPEN, AUTHORIZED, CAPTURING, CAPTURED, VOIDED, REFUNDING, REFUNDED, REVIEW_REQUIRED }

    public enum Kind { ORDER, TIP }

    record Payment(long orderId, UUID reference, Kind kind, State state, boolean live, long authorizedCents,
            Long captureCents, OffsetDateTime expiresAt, String returnUrl, String sessionId, String checkoutUrl,
            String intentId, String chargeId, String refundId, OffsetDateTime closingAt, OffsetDateTime captureUntil,
            UUID leaseToken, int failures, OffsetDateTime checkoutCompletedAt, String reviewReason, String reviewProviderState) {}

    public record CustomerPayment(State state, long authorizedCents, Long capturedCents, String currency,
            String checkoutUrl, OffsetDateTime checkoutExpiresAt) {}

    public record AdminPayment(State state, boolean live, long authorizedCents, Long captureCents,
            String sessionId, String intentId, String chargeId, String refundId, String reviewReason, String reviewProviderState) {}

    public record PaymentReview(long orderId, Kind kind, boolean live, String reason) {}

    private final JdbcClient jdbc;
    private final boolean enabled;
    private final boolean live;
    private final String returnUrl;

    OrderPayments(JdbcClient jdbc, Environment environment, @Value("${app.payments.enabled}") boolean enabled,
            @Value("${app.payments.live}") boolean live, @Value("${app.payments.return-url}") String returnUrl) {
        if (environment.matchesProfiles("prod") && (!enabled || !live)) {
            throw new IllegalStateException("The prod profile requires STRIPE_ENABLED=true and STRIPE_LIVE=true");
        }
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.live = live;
        this.returnUrl = enabled ? PaymentConfig.validateReturnUrl(returnUrl, live) : "";
    }

    public boolean enabled() {
        return enabled;
    }

    public void validateAmount(long cents) {
        if (enabled && (cents < 50 || cents > 99_999_999)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stripe payments require 50 to 99,999,999 CAD cents");
        }
    }

    public void open(long orderId, long cents) {
        jdbc.sql("""
                insert into order_payments (order_id, reference, kind, state, live, authorized_cents, expires_at, return_url, due_at)
                values (:id, :reference, 'ORDER', 'OPENING', :live, :cents, now() + interval '10 minutes', :url, now())
                """).param("id", orderId).param("reference", UUID.randomUUID()).param("live", live)
                .param("cents", cents).param("url", returnUrl).update();
    }

    public boolean exists(long orderId) {
        return jdbc.sql("select exists(select 1 from order_payments where order_id = :id and kind = 'ORDER')")
                .param("id", orderId).query(Boolean.class).single();
    }

    public void capture(long orderId, long cents) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Stripe processing is unavailable");
        }
        validateAmount(cents);
        int updated = jdbc.sql("""
                update order_payments set state = 'CAPTURING', capture_cents = :cents, due_at = now(),
                    capture_until = now() + interval '15 minutes'
                where order_id = :id and kind = 'ORDER' and state = 'AUTHORIZED' and live = :live
                """).param("id", orderId).param("cents", cents).param("live", live).update();
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The order does not have an active authorization");
        }
    }

    public void close(long orderId) {
        jdbc.sql("""
                update order_payments set closing_at = coalesce(closing_at, now()), due_at = now()
                where order_id = :id and kind = 'ORDER' and state in ('OPENING', 'OPEN', 'AUTHORIZED', 'CAPTURING', 'CAPTURED')
                """).param("id", orderId).update();
    }

    public CustomerPayment customer(long orderId, long customerId, Kind kind) {
        return jdbc.sql("""
                select p.*, o.status as order_status from order_payments p join orders o on o.id = p.order_id
                where p.order_id = :id and o.customer_id = :customer and p.kind = :kind
                """).param("id", orderId).param("customer", customerId).param("kind", kind.name()).query((rs, row) -> {
                    Payment p = read(rs, row);
                    String expected = kind == Kind.ORDER ? "PAYMENT_PENDING" : "DELIVERED";
                    boolean open = enabled && p.live() == live && p.state() == State.OPEN
                            && expected.equals(rs.getString("order_status"))
                            && p.expiresAt().toInstant().isAfter(Instant.now());
                    return new CustomerPayment(p.state(), p.authorizedCents(), p.chargeId() == null ? null : p.captureCents(),
                            "CAD", open ? p.checkoutUrl() : null, p.expiresAt());
                }).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order payment not found"));
    }

    public AdminPayment admin(long orderId, Kind kind) {
        return find(orderId, kind).map(p -> new AdminPayment(p.state(), p.live(), p.authorizedCents(), p.captureCents(),
                p.sessionId(), p.intentId(), p.chargeId(), p.refundId(), p.reviewReason(), p.reviewProviderState()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order payment not found"));
    }

    public List<PaymentReview> needsReview(int page, int size) {
        var paging = Paging.of(page, size);
        return jdbc.sql("""
                select order_id, kind, live, review_reason from order_payments
                where state = 'REVIEW_REQUIRED'
                order by order_id, kind offset :offset limit :limit
                """).param("offset", paging.getOffset()).param("limit", paging.getPageSize())
                .query((rs, row) -> new PaymentReview(rs.getLong("order_id"), Kind.valueOf(rs.getString("kind")),
                        rs.getBoolean("live"), rs.getString("review_reason"))).list();
    }

    public boolean orderCaptured(long orderId) {
        return find(orderId, Kind.ORDER).filter(p -> p.state() == State.CAPTURED && p.live() == live).isPresent();
    }

    private Optional<Payment> find(long id, Kind kind) {
        return jdbc.sql("select * from order_payments where order_id = :id and kind = :kind")
                .param("id", id).param("kind", kind.name()).query(OrderPayments::read).optional();
    }

    public Optional<CustomerPayment> existingTip(long orderId, long customerId, int cents) {
        return find(orderId, Kind.TIP).filter(p -> p.state() != State.VOIDED).map(p -> {
            if (p.authorizedCents() != cents) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A different tip is already in progress or paid");
            }
            return customer(orderId, customerId, Kind.TIP);
        });
    }

    public CustomerPayment openTip(long orderId, long customerId, int cents, Instant lastTipAt) {
        if (lastTipAt.isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The 24-hour tip window has ended");
        }
        validateAmount(cents);
        jdbc.sql("""
                insert into order_payments (order_id, reference, kind, state, live, authorized_cents, capture_cents,
                                            expires_at, return_url, due_at)
                values (:id, :reference, 'TIP', 'OPENING', :live, :cents, :cents,
                        least(:deadline, now() + interval '10 minutes'), :url, now())
                on conflict (order_id, kind) do update set reference = excluded.reference, state = 'OPENING',
                    authorized_cents = excluded.authorized_cents, capture_cents = excluded.capture_cents,
                    expires_at = excluded.expires_at, return_url = excluded.return_url, due_at = now(),
                    session_id = null, checkout_url = null, intent_id = null, closing_at = null, capture_until = null, review_reason = null,
                    checkout_completed_at = null, review_provider_state = null
                where order_payments.state = 'VOIDED' and order_payments.live = excluded.live
                """).param("id", orderId).param("reference", UUID.randomUUID()).param("live", live).param("cents", cents)
                .param("deadline", lastTipAt.atOffset(ZoneOffset.UTC)).param("url", returnUrl).update();
        return customer(orderId, customerId, Kind.TIP);
    }

    void creditTip(Payment payment) {
        // Serialize tip booking and period settlement at the payroll cutoff.
        jdbc.sql("""
                select d.user_id from drivers d join orders o on o.driver_id = d.user_id
                where o.id = :id for update of d
                """).param("id", payment.orderId()).query(Long.class).single();
        int credited = jdbc.sql("""
                update orders set tip_cents = :cents where id = :id and status = 'DELIVERED' and tip_cents = 0
                """).param("id", payment.orderId()).param("cents", payment.captureCents()).update();
        if (credited != 1) {
            throw new IllegalStateException("Captured tip could not be credited to the order");
        }
    }

    public List<Long> expiredUnopened() {
        return jdbc.sql("""
                select p.order_id from order_payments p join orders o on o.id = p.order_id
                where p.kind = 'ORDER' and p.state = 'OPENING' and p.expires_at <= now() and o.status = 'PAYMENT_PENDING'
                order by p.order_id limit 200
                """).query(Long.class).list();
    }

    void wake(String sessionId, boolean live, Instant completedAt) {
        jdbc.sql("""
                update order_payments set due_at = now(), webhook_pending = true,
                    checkout_completed_at = least(checkout_completed_at, cast(:completed as timestamptz))
                where session_id = :session and live = :live and state = 'OPEN'
                    and (not webhook_pending or cast(:completed as timestamptz) < checkout_completed_at
                        or (checkout_completed_at is null and cast(:completed as timestamptz) is not null))
                """).param("session", sessionId).param("live", live)
                .param("completed", completedAt == null ? null : completedAt.atOffset(ZoneOffset.UTC)).update();
    }

    Optional<Payment> claim(boolean live) {
        return jdbc.sql("""
                with next as (
                    select reference from order_payments
                    where due_at <= now() and (lease_until is null or lease_until <= now()) and live = :live
                    order by due_at, order_id for update skip locked limit 1
                )
                update order_payments p set lease_until = now() + interval '60 seconds', lease_token = :lease,
                    webhook_pending = false
                from next where p.reference = next.reference returning p.*
                """).param("live", live).param("lease", UUID.randomUUID()).query(OrderPayments::read).optional();
    }

    Optional<Payment> lock(Payment payment) {
        return jdbc.sql("select * from order_payments where reference = :id and lease_token = :lease for update")
                .param("id", payment.reference()).param("lease", payment.leaseToken()).query(OrderPayments::read).optional();
    }

    void save(Payment payment, State state, String session, String url, String intent, String charge,
            String refund, Integer delaySeconds, String reviewReason) {
        jdbc.sql("""
                update order_payments
                set state = :state, session_id = coalesce(:session, session_id), checkout_url = :url,
                    intent_id = coalesce(:intent, intent_id), charge_id = coalesce(:charge, charge_id),
                    refund_id = coalesce(:refund, refund_id), failures = 0, review_reason = :reason,
                    captured_at = case when :state = 'CAPTURED' then coalesce(captured_at, clock_timestamp()) else captured_at end,
                    capture_until = case when :state = 'CAPTURING' then coalesce(capture_until, now() + interval '15 minutes')
                                         else capture_until end,
                    due_at = case when :state = 'OPEN' and webhook_pending then now()
                                  else now() + make_interval(secs => cast(:delay as integer)) end,
                    webhook_pending = (:state = 'OPEN' and webhook_pending), lease_until = null, lease_token = null
                where reference = :id and lease_token = :lease
                """).param("id", payment.reference()).param("lease", payment.leaseToken()).param("state", state.name())
                .param("session", session).param("url", url).param("intent", intent).param("charge", charge)
                .param("refund", refund).param("delay", delaySeconds).param("reason", reviewReason).update();
    }

    void recordFinalizationReview(Payment p, State providerState, String session, String intent,
            String charge, String refund) {
        jdbc.sql("""
                update order_payments set state = 'REVIEW_REQUIRED', review_reason = 'LOCAL_FINALIZATION_FAILED',
                    review_provider_state = :providerState, session_id = coalesce(:session, session_id),
                    intent_id = coalesce(:intent, intent_id), charge_id = coalesce(:charge, charge_id),
                    refund_id = coalesce(:refund, refund_id), checkout_url = null,
                    due_at = null, lease_until = null, lease_token = null
                where reference = :id and lease_token = :lease
                """).param("id", p.reference()).param("lease", p.leaseToken()).param("providerState", providerState.name())
                .param("session", session).param("intent", intent).param("charge", charge).param("refund", refund).update();
    }

    void retry(Payment p) {
        retry(p, null);
    }

    void retry(Payment p, String confirmedCharge) {
        jdbc.sql("""
                update order_payments set lease_until = null, lease_token = null, failures = failures + 1,
                    charge_id = coalesce(:charge, charge_id), due_at = now() + make_interval(secs => :delay)
                where reference = :id and lease_token = :lease
                """).param("id", p.reference()).param("lease", p.leaseToken())
                .param("charge", confirmedCharge)
                .param("delay", Math.min(60, 1 << Math.min(p.failures() + 1, 6)) + ThreadLocalRandom.current().nextInt(3)).update();
    }

    private static Payment read(ResultSet rs, int row) throws SQLException {
        return new Payment(rs.getLong("order_id"), rs.getObject("reference", UUID.class), Kind.valueOf(rs.getString("kind")),
                State.valueOf(rs.getString("state")), rs.getBoolean("live"), rs.getLong("authorized_cents"),
                rs.getObject("capture_cents", Long.class), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getString("return_url"), rs.getString("session_id"), rs.getString("checkout_url"),
                rs.getString("intent_id"), rs.getString("charge_id"), rs.getString("refund_id"),
                rs.getObject("closing_at", OffsetDateTime.class), rs.getObject("capture_until", OffsetDateTime.class),
                rs.getObject("lease_token", UUID.class),
                rs.getInt("failures"), rs.getObject("checkout_completed_at", OffsetDateTime.class),
                rs.getString("review_reason"), rs.getString("review_provider_state"));
    }
}
