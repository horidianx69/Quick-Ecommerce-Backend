package com.quickcommerce.payment;

import com.quickcommerce.order.Order;
import com.quickcommerce.order.Order.Status;
import com.quickcommerce.order.OrderRepository;
import com.quickcommerce.order.OrderService;
import com.quickcommerce.payment.OrderPayments.Kind;
import com.quickcommerce.payment.OrderPayments.Payment;
import com.quickcommerce.payment.OrderPayments.State;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
import com.stripe.param.checkout.SessionCreateParams;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "app.payments.enabled", havingValue = "true")
class StripeOrderWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(StripeOrderWorker.class);
    private static final String REFERENCE = "quike_payment";
    private static final String CAD = "cad";
    private static final Set<Status> CLOSED = Set.of(Status.CANCELLED, Status.REJECTED, Status.EXPIRED);
    private static final int RECHECK_SECONDS = 30;
    private static final int DRAIN_ROUNDS = 4;

    private record Result(State state, String session, String url, String intent, String charge,
            String refund, Integer delaySeconds, String reviewReason) {
        Result(State state, String session, String url, String intent, String charge, String refund, Integer delaySeconds) {
            this(state, session, url, intent, charge, refund, delaySeconds,
                    state == State.REVIEW_REQUIRED ? "UNEXPECTED_STRIPE_STATE" : null);
        }

        Result needsReview(String reason) {
            return new Result(State.REVIEW_REQUIRED, session, null, intent, charge, refund, null, reason);
        }
        static Result state(State state) {
            return new Result(state, null, null, null, null, null, null);
        }
    }

    private final OrderPayments payments;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final StripeClient stripe;
    private final StripeSettlements settlements;
    private final TransactionTemplate tx;
    private final boolean live;
    private final int concurrency;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    StripeOrderWorker(OrderPayments payments, OrderRepository orders, OrderService orderService, StripeClient stripe,
            StripeSettlements settlements, TransactionTemplate tx, @Value("${app.payments.live}") boolean live,
            @Value("${app.payments.worker-concurrency}") int concurrency) {
        this.payments = payments;
        this.orders = orders;
        this.orderService = orderService;
        this.stripe = stripe;
        this.settlements = settlements;
        this.tx = tx;
        this.live = live;
        if (concurrency < 1 || concurrency > 16) {
            throw new IllegalStateException("Stripe worker concurrency must be between 1 and 16");
        }
        this.concurrency = concurrency;
    }

    @Scheduled(fixedDelayString = "${app.payments.poll-ms}", scheduler = "paymentScheduler")
    void drain() throws InterruptedException {
        var tasks = IntStream.range(0, concurrency).<Callable<Void>>mapToObj(slot -> () -> {
            for (int i = 0; i < DRAIN_ROUNDS; i++) {
                var next = payments.claim(live);
                next.ifPresent(this::run);
                boolean transferred = settlements.processOne();
                if (next.isEmpty() && !transferred) {
                    break;
                }
            }
            return null;
        }).toList();
        for (var task : workers.invokeAll(tasks)) {
            try {
                task.get();
            } catch (ExecutionException e) {
                log.error("Stripe payment worker iteration failed: {}", e.getCause().getClass().getSimpleName());
            }
        }
    }

    private void run(Payment payment) {
        try {
            Status status = orders.findById(payment.orderId()).orElseThrow().getStatus();
            boolean tipExpired = payment.kind() == Kind.TIP && payment.state() != State.CAPTURING
                    && !payment.expiresAt().toInstant().isAfter(Instant.now());
            Result result = reconcile(payment, CLOSED.contains(status) || tipExpired);
            if (result.state() == payment.state() && result.delaySeconds() != null && retryDeadlinePassed(payment)) {
                result = result.needsReview("STRIPE_RESULT_UNRESOLVED");
            }
            finish(payment, result);
        } catch (StripeException e) {
            if (retryDeadlinePassed(payment)) {
                log.warn("Stripe operation reached its recovery deadline for order {}, stage {}, HTTP {}, code {}, request {}",
                        payment.orderId(), payment.state(), e.getStatusCode(), e.getCode(), e.getRequestId());
                finish(payment, Result.state(State.REVIEW_REQUIRED).needsReview("STRIPE_API_RETRY_DEADLINE"));
            } else {
                log.warn("Stripe operation will retry for order {}, stage {}, HTTP {}, code {}, request {}",
                        payment.orderId(), payment.state(), e.getStatusCode(), e.getCode(), e.getRequestId());
                payments.retry(payment);
            }
        } catch (RuntimeException e) {
            log.error("Payment reconciliation will retry for order {}, stage {}, failure {}",
                    payment.orderId(), payment.state(), e.getClass().getSimpleName());
            payments.retry(payment);
        }
    }

    private static boolean retryDeadlinePassed(Payment p) {
        OffsetDateTime deadline = switch (p.state()) {
            case OPEN, AUTHORIZED ->
                (p.closingAt() == null ? p.expiresAt() : p.closingAt()).plusHours(1);
            case CAPTURING -> p.captureUntil() == null ? null : p.captureUntil().plusMinutes(15);
            case CAPTURED -> p.closingAt() == null ? null : p.closingAt().plusHours(20);
            case REFUNDING -> p.closingAt() == null ? null : p.closingAt().plusDays(7);
            default -> null;
        };
        return deadline != null && !deadline.toInstant().isAfter(Instant.now());
    }

    private Result reconcile(Payment p, boolean closed) throws StripeException {
        if (p.state() == State.OPENING) {
            return closed || !Instant.now().isBefore(p.expiresAt().toInstant())
                    ? Result.state(State.VOIDED) : open(p);
        }
        if (p.state() == State.OPEN) {
            return syncSession(p, closed);
        }
        if (p.state() == State.CAPTURING || p.state() == State.AUTHORIZED) {
            return syncIntent(p, p.intentId(), closed);
        }
        if ((p.state() == State.CAPTURED && closed) || p.state() == State.REFUNDING) {
            return refund(p);
        }
        return Result.state(p.state());
    }

    private Result open(Payment p) throws StripeException {
        var price = SessionCreateParams.LineItem.PriceData.builder().setCurrency(CAD).setUnitAmount(p.authorizedCents())
                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                        .setName((p.kind() == Kind.TIP ? "QuikE driver tip, order #" : "QuikE order #") + p.orderId()).build()).build();
        var params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setAdaptivePricing(SessionCreateParams.AdaptivePricing.builder().setEnabled(false).build())
                .addAllowedPaymentMethodType(SessionCreateParams.AllowedPaymentMethodType.CARD)
                .setClientReferenceId(p.reference().toString())
                .setExpiresAt(p.expiresAt().plusMinutes(35).toEpochSecond())
                .setSuccessUrl(returnUrl(p))
                .setCancelUrl(returnUrl(p))
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .setCaptureMethod(SessionCreateParams.PaymentIntentData.CaptureMethod.MANUAL)
                        .putMetadata(REFERENCE, p.reference().toString()).build())
                .addLineItem(SessionCreateParams.LineItem.builder().setQuantity(1L).setPriceData(price).build())
                .build();
        Session session = stripe.v1().checkout().sessions().create(params, options(p, "open"));
        if (!matches(p, session) || session.getId() == null || !validCheckoutUrl(session.getUrl())) {
            return Result.state(State.REVIEW_REQUIRED);
        }
        return new Result(State.OPEN, session.getId(), session.getUrl(), null, null, null, RECHECK_SECONDS);
    }

    private Result syncSession(Payment p, boolean closed) throws StripeException {
        Session session = stripe.v1().checkout().sessions().retrieve(p.sessionId());
        if (!p.sessionId().equals(session.getId()) || !matches(p, session)) {
            return Result.state(State.REVIEW_REQUIRED);
        }
        if ("open".equals(session.getStatus())) {
            if (closed || Instant.now().isAfter(p.expiresAt().toInstant())) {
                Session expired = stripe.v1().checkout().sessions().expire(p.sessionId(), options(p, "expire"));
                return p.sessionId().equals(expired.getId()) && "expired".equals(expired.getStatus())
                        ? Result.state(State.VOIDED) : Result.state(State.REVIEW_REQUIRED);
            }
            return new Result(State.OPEN, null, p.checkoutUrl(), null, null, null, RECHECK_SECONDS);
        }
        if ("expired".equals(session.getStatus())) {
            return Result.state(State.VOIDED);
        }
        if (!"complete".equals(session.getStatus()) || session.getPaymentIntent() == null) {
            return Result.state(State.REVIEW_REQUIRED);
        }
        return syncIntent(p, session.getPaymentIntent(), closed);
    }

    private Result syncIntent(Payment p, String intentId, boolean closed) throws StripeException {
        PaymentIntent intent = stripe.v1().paymentIntents().retrieve(intentId);
        if (!matches(p, intentId, intent)) {
            return Result.state(State.REVIEW_REQUIRED);
        }
        if ("canceled".equals(intent.getStatus())) {
            return Result.state(State.VOIDED);
        }
        if ("requires_capture".equals(intent.getStatus())) {
            if (closed || (p.captureUntil() != null && !p.captureUntil().toInstant().isAfter(Instant.now()))) {
                PaymentIntent cancelled = stripe.v1().paymentIntents().cancel(intentId, options(p, "void"));
                return intentId.equals(cancelled.getId()) && "canceled".equals(cancelled.getStatus())
                        ? new Result(State.VOIDED, null, null, intentId, null, null, null)
                        : Result.state(State.REVIEW_REQUIRED);
            }
            if (!Objects.equals(intent.getAmountCapturable(), p.authorizedCents())) {
                return Result.state(State.REVIEW_REQUIRED);
            }
            if (p.state() != State.CAPTURING) {
                return new Result(State.AUTHORIZED, null, null, intentId, null, null, null);
            }
            var params = PaymentIntentCaptureParams.builder().setAmountToCapture(p.captureCents()).build();
            intent = stripe.v1().paymentIntents().capture(intentId, params, options(p, "capture"));
        }
        if (p.state() == State.CAPTURING && matches(p, intentId, intent)
                && "succeeded".equals(intent.getStatus()) && Objects.equals(intent.getAmountReceived(), p.captureCents())
                && intent.getLatestCharge() != null) {
            return new Result(State.CAPTURED, null, null, intentId, intent.getLatestCharge(), null, null);
        }
        if ("processing".equals(intent.getStatus())) {
            return new Result(p.state(), null, p.checkoutUrl(), intentId, null, null, RECHECK_SECONDS);
        }
        return Result.state(State.REVIEW_REQUIRED);
    }

    private Result refund(Payment p) throws StripeException {
        Refund refund = p.refundId() == null ? null : stripe.v1().refunds().retrieve(p.refundId());
        boolean sendWindowElapsed = retryDeadlinePassed(p);
        if (refund == null && (p.failures() > 0 || sendWindowElapsed)) {
            var page = stripe.v1().refunds().list(RefundListParams.builder().setPaymentIntent(p.intentId())
                    .setLimit(20L).build());
            var matched = page.getData().stream().filter(r -> r.getMetadata() != null
                    && p.reference().toString().equals(r.getMetadata().get(REFERENCE))).toList();
            if (Boolean.TRUE.equals(page.getHasMore()) || matched.size() > 1) {
                return Result.state(State.REVIEW_REQUIRED);
            }
            refund = matched.isEmpty() ? null : matched.getFirst();
        }
        if (refund == null) {
            // Stripe can drop idempotency keys after 24 hours. Never resend an unknown money movement after that window.
            if (p.closingAt() == null || sendWindowElapsed) {
                return Result.state(State.REVIEW_REQUIRED).needsReview("REFUND_RESULT_UNRESOLVED");
            }
            refund = stripe.v1().refunds().create(RefundCreateParams.builder().setPaymentIntent(p.intentId())
                    .setAmount(p.captureCents()).putMetadata(REFERENCE, p.reference().toString()).build(), options(p, "refund"));
        }
        if (refund.getId() == null || !p.intentId().equals(refund.getPaymentIntent())
                || !CAD.equals(refund.getCurrency()) || !Objects.equals(p.captureCents(), refund.getAmount())
                || refund.getMetadata() == null || !p.reference().toString().equals(refund.getMetadata().get(REFERENCE))) {
            return Result.state(State.REVIEW_REQUIRED);
        }
        State state = switch (refund.getStatus() == null ? "" : refund.getStatus()) {
            case "succeeded" -> State.REFUNDED;
            case "pending", "requires_action" -> State.REFUNDING;
            default -> State.REVIEW_REQUIRED;
        };
        return new Result(state, null, null, null, null, refund.getId(),
                state == State.REFUNDING ? RECHECK_SECONDS : null);
    }

    private void finish(Payment payment, Result result) {
        tx.executeWithoutResult(ignored -> {
            Order order = orders.findWithLockById(payment.orderId()).orElseThrow();
            payments.lock(payment).ifPresent(current -> {
                State state = result.state();
                Integer delay = result.delaySeconds();
                Instant now = Instant.now();
                switch (state) {
                    case AUTHORIZED -> {
                        if (payment.kind() == Kind.TIP) {
                            if (now.isBefore(payment.expiresAt().toInstant())) {
                                state = State.CAPTURING;
                            }
                            delay = 0;
                        } else if (order.getStatus() == Status.PAYMENT_PENDING) {
                            if (now.isBefore(payment.expiresAt().toInstant())) {
                                orderService.confirmAuthorization(order.getId(), now);
                            } else {
                                orderService.expirePaymentOrder(order, Status.PAYMENT_PENDING);
                                delay = 0;
                            }
                        }
                    }
                    case CAPTURED -> {
                        if (payment.kind() == Kind.TIP) {
                            payments.creditTip(current);
                        } else if (order.getStatus() == Status.CAPTURE_PENDING) {
                            orderService.confirmCapture(order.getId());
                        }
                    }
                    case REVIEW_REQUIRED, VOIDED -> {
                        if (state == State.REVIEW_REQUIRED) {
                            log.error("Stripe payment for order {} requires reconciliation", payment.orderId());
                        }
                        if (payment.kind() == Kind.ORDER && (order.getStatus() == Status.PAYMENT_PENDING
                                || order.getStatus() == Status.CAPTURE_PENDING)) {
                            orderService.expirePaymentOrder(order, order.getStatus());
                        }
                    }
                    default -> { }
                }
                if (CLOSED.contains(order.getStatus())
                        && (state == State.OPEN || state == State.AUTHORIZED)
                        && current.closingAt() != null && payment.closingAt() == null) {
                    delay = 0;
                }
                payments.save(current, state, result.session(), result.url(), result.intent(), result.charge(),
                        result.refund(), delay, result.reviewReason());
            });
        });
    }

    private static String returnUrl(Payment p) {
        return p.returnUrl() + "?orderId=" + p.orderId() + (p.kind() == Kind.TIP ? "&kind=tip" : "");
    }

    private static boolean matches(Payment p, Session s) {
        return Objects.equals(p.live(), s.getLivemode()) && "payment".equals(s.getMode()) && CAD.equals(s.getCurrency())
                && Objects.equals(p.authorizedCents(), s.getAmountTotal())
                && p.reference().toString().equals(s.getClientReferenceId());
    }

    private static boolean matches(Payment p, String intentId, PaymentIntent i) {
        return intentId.equals(i.getId()) && Objects.equals(p.live(), i.getLivemode()) && CAD.equals(i.getCurrency())
                && "manual".equals(i.getCaptureMethod()) && i.getMetadata() != null
                && p.reference().toString().equals(i.getMetadata().get(REFERENCE))
                && (Objects.equals(p.authorizedCents(), i.getAmount())
                        || (p.captureCents() != null && p.captureCents().equals(i.getAmount())));
    }

    private static boolean validCheckoutUrl(String value) {
        if (value == null) {
            return false;
        }
        URI uri = URI.create(value);
        return "https".equals(uri.getScheme()) && "checkout.stripe.com".equals(uri.getHost())
                && uri.getUserInfo() == null && uri.getPort() == -1;
    }

    private static RequestOptions options(Payment payment, String operation) {
        return RequestOptions.builder().setIdempotencyKey("quike-order-" + payment.reference() + "-" + operation).build();
    }

    @Override
    public void destroy() {
        workers.shutdownNow();
    }
}
