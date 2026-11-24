package com.quickcommerce.work;

import com.quickcommerce.common.DatabaseTime;
import com.quickcommerce.common.Settings;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read side of driver work: two-week pay statements, violations and admin alerts.
 *
 * Pay periods are 14 days starting on app.pay.period-anchor (a Tuesday), in the
 * business time zone. An engagement counts in the period it ended in; a tip in
 * the period its order was delivered in, or when a separate Stripe tip was captured.
 * Pay day is the Tuesday after the period.
 */
@Service
public class WorkReports {

    static final int PERIOD_DAYS = 14;

    public record Period(LocalDate start, LocalDate lastDay, LocalDate payDay) {}

    /** earningsCents = engaged-time pay + km allowance. totalCents = earnings + tips. */
    public record PayStatement(Period period, int deliveries, int engagedMinutes, double km, int earningsCents,
            int tipsCents, int totalCents, Instant paidAt) {}

    public record DriverPay(long driverId, String name, PayStatement statement) {}

    public record Violation(long id, Long orderId, String type, String detail, Instant occurredAt) {}

    /** The driver's standing: counts against the two limits, and the latest violations. */
    public record ViolationSummary(int today, int last30Days, int dailyLimit, int monthlyLimit, List<Violation> recent) {}

    /** todayCount and last30DaysCount are live counts, not the count when the alert was raised. */
    public record Alert(long id, long driverId, String driverName, String kind, Long orderId, LocalDate day,
            int violationCount, int todayCount, int last30DaysCount, Instant createdAt, Instant resolvedAt,
            String resolution) {}

    private final JdbcClient jdbc;
    private final DriverWork work;
    private final Settings settings;
    private final DatabaseTime time;
    private final LocalDate anchor;

    WorkReports(DataSource dataSource, DriverWork work, Settings settings, DatabaseTime time,
            @Value("${app.pay.period-anchor}") LocalDate anchor) {
        this.jdbc = JdbcClient.create(dataSource);
        this.work = work;
        this.settings = settings;
        this.time = time;
        this.anchor = anchor;
    }

    // ---------------------------------------------------------------- pay

    public Period periodOf(LocalDate day) {
        long index = Math.floorDiv(ChronoUnit.DAYS.between(anchor, day), PERIOD_DAYS);
        LocalDate start = anchor.plusDays(index * PERIOD_DAYS);
        return new Period(start, start.plusDays(PERIOD_DAYS - 1), start.plusDays(PERIOD_DAYS));
    }

    /** The period starting on this date. 400 if the date is not a period start. */
    public Period periodStarting(LocalDate start) {
        Period period = periodOf(start);
        if (!period.start().equals(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Not a pay period start. Periods start every 14 days from " + anchor);
        }
        return period;
    }

    public Period currentPeriod() {
        return periodOf(time.today());
    }

    /** The driver's statements, the current period first, then the ones before it. */
    public List<PayStatement> statements(long driverId, int count) {
        List<PayStatement> result = new ArrayList<>();
        Period period = currentPeriod();
        for (int i = 0; i < count; i++) {
            Period p = period;
            result.add(pay(p, driverId).stream().findFirst().map(DriverPay::statement)
                    .orElse(new PayStatement(p, 0, 0, 0, 0, 0, 0, null)));
            period = periodOf(period.start().minusDays(1));
        }
        return result;
    }

    /** Every driver who worked or was tipped in the period, A to Z. */
    public List<DriverPay> pay(Period period) {
        return pay(period, 0);
    }

    private List<DriverPay> pay(Period period, long driverId) {
        Timestamp from = Timestamp.from(time.dayStart(period.start()));
        Timestamp to = Timestamp.from(time.dayStart(period.payDay()));
        return jdbc.sql("""
                with eng as (
                    select driver_id, count(*) filter (where end_reason = 'DELIVERED') as deliveries,
                           sum(engaged_seconds) as secs, sum(paid_km) as km, sum(pay_cents) as pay
                    from driver_engagements
                    where ended_at >= :from and ended_at < :to and (:driver = 0 or driver_id = :driver)
                    group by driver_id
                ), tips as (
                    select driver_id, sum(amount) as tips
                    from (
                        select o.driver_id, o.tip_cents as amount
                        from orders o
                        where o.status = 'DELIVERED' and o.driver_id is not null
                            and o.delivered_at >= :from and o.delivered_at < :to
                            and (:driver = 0 or o.driver_id = :driver)
                            and not exists (select 1 from order_payments p where p.order_id = o.id
                                            and p.kind = 'TIP' and p.state = 'CAPTURED')
                        union all
                        select o.driver_id, p.capture_cents
                        from order_payments p join orders o on o.id = p.order_id
                        where p.kind = 'TIP' and p.state = 'CAPTURED' and p.captured_at >= :from and p.captured_at < :to
                            and (:driver = 0 or o.driver_id = :driver)
                    ) entries group by driver_id
                ), ids as (select driver_id from eng union select driver_id from tips)
                select ids.driver_id, u.name, coalesce(e.deliveries, 0) as deliveries, coalesce(e.secs, 0) as secs,
                       coalesce(e.km, 0) as km, coalesce(e.pay, 0) as pay, coalesce(t.tips, 0) as tips, p.paid_at
                from ids
                join users u on u.id = ids.driver_id
                left join eng e on e.driver_id = ids.driver_id
                left join tips t on t.driver_id = ids.driver_id
                left join driver_payouts p on p.driver_id = ids.driver_id and p.period_start = :start
                order by u.name, ids.driver_id
                """)
                .param("from", from).param("to", to).param("driver", driverId).param("start", period.start())
                .query((rs, row) -> {
                    int pay = rs.getInt("pay");
                    int tips = rs.getInt("tips");
                    Timestamp paid = rs.getTimestamp("paid_at");
                    return new DriverPay(rs.getLong("driver_id"), rs.getString("name"), new PayStatement(period,
                            rs.getInt("deliveries"), (int) Math.round(rs.getLong("secs") / 60.0),
                            Math.round(rs.getDouble("km") * 10) / 10.0, pay, tips, pay + tips,
                            paid == null ? null : paid.toInstant()));
                })
                .list();
    }

    /**
     * Admin's record that the driver was paid for the period (the bank transfer
     * itself happens outside the app until payments are built). Only for a period
     * that has ended; 409 if already marked paid.
     */
    @Transactional
    public DriverPay markPaid(long driverId, Period period) {
        jdbc.sql("select user_id from drivers where user_id = :id for update").param("id", driverId)
                .query(Long.class).optional();
        if (jdbc.sql("""
                select exists(select 1 from stripe_transfers where kind = 'DRIVER' and recipient_id = :id
                    and period_start = :start)
                """).param("id", driverId).param("start", period.start()).query(Boolean.class).single()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This pay period has a Stripe transfer");
        }
        DriverPay pay = requirePayable(driverId, period);
        int inserted = jdbc.sql("""
                insert into driver_payouts (driver_id, period_start, amount_cents) values (:d, :start, :amount)
                on conflict do nothing
                """)
                .param("d", driverId).param("start", period.start()).param("amount", pay.statement().totalCents()).update();
        if (inserted == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already marked paid");
        }
        return pay(period, driverId).get(0);
    }

    public DriverPay requirePayable(long driverId, Period period) {
        if (!time.today().isAfter(period.lastDay())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This period has not ended yet");
        }
        return pay(period, driverId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nothing earned in this period"));
    }

    // ---------------------------------------------------------- violations

    public ViolationSummary violations(long driverId, int days) {
        Settings.Values s = settings.current();
        int today = work.countSince(driverId, time.dayStart(time.today()));
        int month = work.countSince(driverId, Instant.now().minus(30, ChronoUnit.DAYS));
        List<Violation> recent = jdbc.sql("""
                select id, order_id, type, detail, occurred_at from driver_violations
                where driver_id = :d and occurred_at >= :since order by occurred_at desc, id desc limit 100
                """)
                .param("d", driverId).param("since", Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)))
                .query((rs, row) -> new Violation(rs.getLong("id"), (Long) rs.getObject("order_id"), rs.getString("type"),
                        rs.getString("detail"), rs.getTimestamp("occurred_at").toInstant()))
                .list();
        return new ViolationSummary(today, month, s.dailyViolationLimit(), s.monthlyViolationLimit(), recent);
    }

    // -------------------------------------------------------------- alerts

    /** Newest first. open=true: only those admin has not resolved. */
    public List<Alert> alerts(boolean open, int limit) {
        return alerts(open, limit, null);
    }

    public Alert alert(long id) {
        return alerts(false, 1, id).stream().findFirst().orElseThrow();
    }

    private List<Alert> alerts(boolean open, int limit, Long id) {
        Timestamp todayStart = Timestamp.from(time.dayStart(time.today()));
        Timestamp monthStart = Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS));
        return jdbc.sql("""
                select a.*, u.name,
                  (select count(*) from driver_violations v where v.driver_id = a.driver_id and v.occurred_at >= :today) as today_count,
                  (select count(*) from driver_violations v where v.driver_id = a.driver_id and v.occurred_at >= :month) as month_count
                from driver_alerts a join users u on u.id = a.driver_id
                where (:open = false or a.resolved_at is null) and (cast(:id as bigint) is null or a.id = :id)
                order by a.created_at desc, a.id desc
                limit :limit
                """)
                .param("today", todayStart).param("month", monthStart).param("open", open).param("limit", limit).param("id", id)
                .query((rs, row) -> new Alert(rs.getLong("id"), rs.getLong("driver_id"), rs.getString("name"),
                        rs.getString("kind"), (Long) rs.getObject("order_id"),
                        rs.getObject("day", LocalDate.class), rs.getInt("violation_count"), rs.getInt("today_count"),
                        rs.getInt("month_count"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
                        rs.getString("resolution")))
                .list();
    }

    /** Marks an open alert resolved and returns its driver id. 409 if already resolved, 404 if unknown. */
    @Transactional
    public long resolve(long alertId, String resolution) {
        Optional<Long> driver = jdbc.sql("""
                update driver_alerts set resolved_at = now(), resolution = :resolution
                where id = :id and resolved_at is null returning driver_id
                """)
                .param("id", alertId).param("resolution", resolution).query(Long.class).optional();
        if (driver.isPresent()) {
            return driver.get();
        }
        boolean exists = jdbc.sql("select count(*) from driver_alerts where id = :id").param("id", alertId)
                .query(Integer.class).single() > 0;
        throw new ResponseStatusException(exists ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND,
                exists ? "Already resolved" : "Alert not found");
    }
}
