package com.quickcommerce.payment;

import com.quickcommerce.common.Paging;
import com.quickcommerce.work.WorkReports;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferListParams;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "app.payments.enabled", havingValue = "true")
public class StripeSettlements {

    private static final Logger log = LoggerFactory.getLogger(StripeSettlements.class);
    private static final Pattern ACCOUNT = Pattern.compile("acct_[A-Za-z0-9]{8,64}");

    public enum Kind { SHOP, DRIVER }
    public enum State { QUEUED, TRANSFERRED, REVIEW_REQUIRED }

    public record Settlement(UUID id, Kind kind, long recipientId, Long orderId, LocalDate periodStart,
            int amountCents, State state, String transferId, String reviewReason) {}

    private record TransferJob(Settlement settlement, String account, String sourceCharge, UUID lease,
            OffsetDateTime firstAttemptAt, int attempts) {}

    private final StripeClient stripe;
    private final JdbcClient jdbc;
    private final WorkReports reports;
    private final TransactionTemplate tx;
    private final boolean live;

    StripeSettlements(StripeClient stripe, JdbcClient jdbc, WorkReports reports, TransactionTemplate tx,
            @Value("${app.payments.live}") boolean live) {
        this.stripe = stripe;
        this.jdbc = jdbc;
        this.reports = reports;
        this.tx = tx;
        this.live = live;
    }

    public void link(Kind kind, long id, String accountId) {
        if (accountId == null || !ACCOUNT.matcher(accountId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Stripe Connect account ID");
        }
        String target = kind == Kind.SHOP
                ? "select exists(select 1 from shops where id = :id and status = 'APPROVED')"
                : "select exists(select 1 from drivers where user_id = :id and status = 'APPROVED')";
        if (!jdbc.sql(target).param("id", id).query(Boolean.class).single()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approved recipient not found");
        }
        try {
            var account = stripe.v1().accounts().retrieve(accountId);
            if (!accountId.equals(account.getId()) || !"CA".equals(account.getCountry())
                    || account.getCapabilities() == null || !"active".equals(account.getCapabilities().getTransfers())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Recipient needs an active Canadian transfers capability");
            }
        } catch (StripeException e) {
            log.warn("Stripe Connect account lookup failed: HTTP {}, request {}", e.getStatusCode(), e.getRequestId());
            var status = Integer.valueOf(404).equals(e.getStatusCode()) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(status, "Stripe Connect account could not be verified");
        }
        jdbc.sql("""
                insert into stripe_recipients (kind, recipient_id, live, account_id)
                values (:kind, :id, :live, :account) on conflict do nothing
                """).param("kind", kind.name()).param("id", id).param("live", live).param("account", accountId).update();
        if (!accountId.equals(account(kind, id).orElse(null))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The recipient or Connect account is already linked");
        }
    }

    public Settlement queueShop(long orderId) {
        return tx.execute(ignored -> {
            var source = jdbc.sql("""
                    select o.shop_id, p.charge_id,
                           (select sum(i.quantity::bigint * i.shop_price_cents) from order_items i
                            where i.order_id = o.id and not i.unavailable) as payout
                    from orders o join order_payments p on p.order_id = o.id
                    where o.id = :order and o.status = 'DELIVERED' and p.state = 'CAPTURED' and p.kind = 'ORDER' and p.live = :live
                    for update of o
                    """).param("order", orderId).param("live", live)
                    .query((rs, row) -> new ShopAmount(rs.getLong("shop_id"), rs.getString("charge_id"), rs.getInt("payout")))
                    .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "A delivered order with a captured Stripe payment is required"));
            return queue(Kind.SHOP, source.shopId(), orderId, null, source.amount(), source.charge());
        });
    }

    private record ShopAmount(long shopId, String charge, int amount) {}

    public Settlement queueDriver(long driverId, WorkReports.Period period) {
        return tx.execute(ignored -> {
            // Serialize Stripe and manual bookkeeping changes for a given driver.
            if (jdbc.sql("select user_id from drivers where user_id = :id for update")
                    .param("id", driverId).query(Long.class).optional().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
            }
            var existing = find(Kind.DRIVER, driverId, null, period.start());
            if (existing.isPresent()) {
                return existing.get();
            }
            var pay = reports.requirePayable(driverId, period);
            if (pay.statement().paidAt() != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The period was already marked paid");
            }
            return queue(Kind.DRIVER, driverId, null, period.start(), pay.statement().totalCents(), null);
        });
    }

    public Settlement get(UUID id) {
        return jdbc.sql("select * from stripe_transfers where id = :id and live = :live")
                .param("id", id).param("live", live).query(StripeSettlements::read).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stripe transfer not found"));
    }

    public List<Settlement> needsReview(int page, int size) {
        var paging = Paging.of(page, size);
        return jdbc.sql("""
                select * from stripe_transfers where state = 'REVIEW_REQUIRED' and live = :live
                order by id offset :offset limit :limit
                """).param("live", live).param("offset", paging.getOffset()).param("limit", paging.getPageSize())
                .query(StripeSettlements::read).list();
    }

    private Settlement queue(Kind type, long recipient, Long order, LocalDate period, int amount, String charge) {
        if (amount < 1 || amount > 99_999_999) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No payable amount within Stripe CAD limits");
        }
        String destination = account(type, recipient).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "Link a verified Stripe Connect recipient first"));
        jdbc.sql("""
                insert into stripe_transfers (id, live, kind, recipient_id, order_id, period_start, account_id,
                                              amount_cents, source_charge, state, due_at)
                values (:id, :live, :kind, :recipient, :order, :period, :account, :amount, :charge, 'QUEUED', now())
                on conflict (kind, recipient_id, live, order_id, period_start) do nothing
                """).param("id", UUID.randomUUID()).param("live", live).param("kind", type.name())
                .param("recipient", recipient).param("order", order).param("period", period).param("account", destination)
                .param("amount", amount).param("charge", charge).update();
        return find(type, recipient, order, period).orElseThrow();
    }

    private Optional<String> account(Kind type, long id) {
        return jdbc.sql("""
                select account_id from stripe_recipients where kind = :kind and live = :live and recipient_id = :id
                """).param("kind", type.name()).param("live", live).param("id", id).query(String.class).optional();
    }

    private Optional<Settlement> find(Kind type, long id, Long order, LocalDate period) {
        return jdbc.sql("""
                select * from stripe_transfers where kind = :kind and live = :live and recipient_id = :id
                    and order_id is not distinct from cast(:order as bigint)
                    and period_start is not distinct from cast(:period as date)
                """).param("kind", type.name()).param("live", live).param("id", id).param("order", order)
                .param("period", period).query(StripeSettlements::read).optional();
    }

    boolean processOne() {
        var claimed = claim();
        claimed.ifPresent(job -> {
            var info = job.settlement();
            try {
                String group = "quike-transfer-" + info.id();
                Transfer transfer = null;
                if (job.attempts() > 1) {
                    var result = stripe.v1().transfers().list(TransferListParams.builder().setDestination(job.account())
                            .setTransferGroup(group).setLimit(2L).build());
                    if (Boolean.TRUE.equals(result.getHasMore()) || result.getData().size() > 1) {
                        finish(job, State.REVIEW_REQUIRED, null, "MULTIPLE_STRIPE_TRANSFERS");
                        return;
                    }
                    if (!result.getData().isEmpty()) {
                        transfer = result.getData().getFirst();
                    }
                }
                if (transfer == null) {
                    if (retryDeadlinePassed(job)) {
                        finish(job, State.REVIEW_REQUIRED, null, "TRANSFER_RESULT_UNRESOLVED");
                        return;
                    }
                    if (job.sourceCharge() != null && !validSourceCharge(job)) {
                        finish(job, State.REVIEW_REQUIRED, null, "SHOP_CHARGE_INELIGIBLE");
                        return;
                    }
                    var params = TransferCreateParams.builder().setAmount((long) info.amountCents()).setCurrency("cad")
                            .setDestination(job.account()).setTransferGroup(group)
                            .putMetadata("quike_transfer", info.id().toString());
                    if (job.sourceCharge() != null) {
                        params.setSourceTransaction(job.sourceCharge());
                    }
                    transfer = stripe.v1().transfers().create(params.build(),
                            RequestOptions.builder().setIdempotencyKey(group).build());
                }
                boolean valid = transfer.getId() != null && Objects.equals(live, transfer.getLivemode())
                        && Objects.equals((long) info.amountCents(), transfer.getAmount()) && "cad".equals(transfer.getCurrency())
                        && job.account().equals(transfer.getDestination())
                        && Objects.equals(job.sourceCharge(), transfer.getSourceTransaction())
                        && group.equals(transfer.getTransferGroup()) && Boolean.FALSE.equals(transfer.getReversed())
                        && transfer.getMetadata() != null && info.id().toString().equals(transfer.getMetadata().get("quike_transfer"));
                finish(job, valid ? State.TRANSFERRED : State.REVIEW_REQUIRED, transfer.getId(),
                        valid ? null : "UNEXPECTED_STRIPE_TRANSFER");
            } catch (StripeException e) {
                log.warn("Stripe transfer {} could not reconcile, HTTP {}, code {}, request {}",
                        info.id(), e.getStatusCode(), e.getCode(), e.getRequestId());
                if (retryDeadlinePassed(job)) {
                    finish(job, State.REVIEW_REQUIRED, null, "STRIPE_API_RETRY_DEADLINE");
                } else {
                    retry(job);
                }
            } catch (RuntimeException e) {
                log.warn("Stripe transfer {} will retry, failure {}", info.id(), e.getClass().getSimpleName());
                retry(job);
            }
        });
        return claimed.isPresent();
    }

    private boolean validSourceCharge(TransferJob job) throws StripeException {
        var charge = stripe.v1().charges().retrieve(job.sourceCharge());
        return job.sourceCharge().equals(charge.getId()) && Objects.equals(live, charge.getLivemode())
                && "cad".equals(charge.getCurrency()) && Boolean.TRUE.equals(charge.getPaid())
                && Boolean.TRUE.equals(charge.getCaptured()) && Boolean.FALSE.equals(charge.getDisputed())
                && Objects.equals(0L, charge.getAmountRefunded()) && charge.getAmountCaptured() != null
                && charge.getAmountCaptured() >= job.settlement().amountCents();
    }

    private static boolean retryDeadlinePassed(TransferJob job) {
        return !job.firstAttemptAt().plusHours(20).isAfter(OffsetDateTime.now());
    }

    private Optional<TransferJob> claim() {
        return jdbc.sql("""
                with next as (
                    select id from stripe_transfers where live = :live and due_at <= now()
                        and (lease_until is null or lease_until <= now())
                    order by due_at, id for update skip locked limit 1
                )
                update stripe_transfers t set lease_until = now() + interval '60 seconds', lease_token = :lease,
                    first_attempt_at = coalesce(first_attempt_at, now()), attempts = attempts + 1
                from next where t.id = next.id returning t.*
                """).param("live", live).param("lease", UUID.randomUUID()).query((rs, row) ->
                    new TransferJob(read(rs, row), rs.getString("account_id"), rs.getString("source_charge"),
                        rs.getObject("lease_token", UUID.class), rs.getObject("first_attempt_at", OffsetDateTime.class),
                        rs.getInt("attempts")))
                .optional();
    }

    private void finish(TransferJob job, State state, String transferId, String reason) {
        var info = job.settlement();
        tx.executeWithoutResult(ignored -> {
            int updated = jdbc.sql("""
                    update stripe_transfers set state = :state, transfer_id = :stripe, review_reason = :reason, due_at = null,
                        lease_until = null, lease_token = null
                    where id = :id and lease_token = :lease
                    """).param("state", state.name()).param("stripe", transferId).param("id", info.id()).param("reason", reason)
                    .param("lease", job.lease()).update();
            if (updated == 1 && state == State.TRANSFERRED && info.kind() == Kind.DRIVER) {
                jdbc.sql("""
                        insert into driver_payouts (driver_id, period_start, amount_cents)
                        values (:driver, :start, :cents)
                        """).param("driver", info.recipientId()).param("start", info.periodStart())
                        .param("cents", info.amountCents()).update();
            }
            if (updated == 1 && state == State.REVIEW_REQUIRED) {
                log.error("Stripe transfer {} requires reconciliation", info.id());
            }
        });
    }

    private void retry(TransferJob job) {
        jdbc.sql("""
                update stripe_transfers set lease_until = null, lease_token = null, due_at = now() + make_interval(secs => :delay)
                where id = :id and lease_token = :lease
                """).param("id", job.settlement().id()).param("lease", job.lease())
                .param("delay", ThreadLocalRandom.current().nextInt(25, 36)).update();
    }

    private static Settlement read(ResultSet rs, int row) throws SQLException {
        return new Settlement(rs.getObject("id", UUID.class), Kind.valueOf(rs.getString("kind")), rs.getLong("recipient_id"),
                rs.getObject("order_id", Long.class), rs.getObject("period_start", LocalDate.class), rs.getInt("amount_cents"),
                State.valueOf(rs.getString("state")), rs.getString("transfer_id"), rs.getString("review_reason"));
    }
}
