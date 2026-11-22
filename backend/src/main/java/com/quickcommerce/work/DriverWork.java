package com.quickcommerce.work;

import com.quickcommerce.common.Settings;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A driver's work, as it happens: engagements (each time a driver holds an
 * order, with engaged time, km and pay), violations, and the admin alerts they
 * raise. Called by the order and dispatch parts inside their own transactions.
 *
 * Engaged time follows the BC and Ontario rules for app-based work: it runs
 * from accepting the job until the job ends (delivered, given back, taken off
 * the driver, or cancelled), including the trip to the shop and waiting there.
 */
@Service
public class DriverWork {

    /** Roads are longer than straight lines; paid km = straight-line km x this. */
    static final double ROAD_FACTOR = 1.3;

    public enum EndReason { DELIVERED, RELEASED, UNASSIGNED, CANCELLED }

    public enum ViolationType { LATE_DELIVERY, LOCATION_LOST, NOT_RECEIVED, JOB_RELEASED, CODE_TRIES_USED }

    public record Engagement(long id, long orderId, long driverId, Instant acceptedAt, Instant pickedUpAt, Instant endedAt,
            String endReason, int engagedSeconds, double paidKm, int payCents, boolean locationLost) {}

    private final JdbcClient jdbc;
    private final Settings settings;
    private final ZoneId businessZone;

    DriverWork(DataSource dataSource, Settings settings, @Value("${app.business-time-zone}") String businessZone) {
        this.jdbc = JdbcClient.create(dataSource);
        this.settings = settings;
        this.businessZone = ZoneId.of(businessZone);
    }

    public ZoneId businessZone() {
        return businessZone;
    }

    // --------------------------------------------------------- engagements

    /**
     * The driver starts holding the order. Unique indexes allow one open
     * engagement per order and per driver, so a race between two accepts
     * fails in the database rather than double-booking.
     */
    @Transactional
    public void start(long orderId, long driverId, double toShopKm, double toCustomerKm) {
        jdbc.sql("""
                insert into driver_engagements (order_id, driver_id, accepted_at, to_shop_km, to_customer_km)
                values (:order, :driver, now(), :toShop, :toCustomer)
                """)
                .param("order", orderId).param("driver", driverId)
                .param("toShop", toShopKm).param("toCustomer", toCustomerKm)
                .update();
    }

    @Transactional
    public void pickedUp(long orderId) {
        jdbc.sql("update driver_engagements set picked_up_at = now() where order_id = :order and ended_at is null")
                .param("order", orderId).update();
    }

    /**
     * Ends the order's open engagement and works out its pay, with today's
     * rates: engaged hours x hourly rate + paid km x per-km rate. Paid km is the
     * trip to the shop, plus the trip to the customer once picked up. Records a
     * late delivery or a given-back job as a violation. Empty if no driver held it.
     */
    @Transactional
    public Optional<Engagement> end(long orderId, EndReason reason) {
        Settings.Values s = settings.current();
        Optional<Engagement> ended = jdbc.sql("""
                update driver_engagements set
                  ended_at = now(),
                  end_reason = :reason,
                  engaged_seconds = greatest(0, round(extract(epoch from now() - accepted_at)))::int,
                  paid_km = round(((to_shop_km + case when picked_up_at is null then 0 else to_customer_km end) * :road)::numeric, 2),
                  pay_cents = round(extract(epoch from now() - accepted_at) * :hourly / 3600.0
                      + (to_shop_km + case when picked_up_at is null then 0 else to_customer_km end) * :road * :perKm)::int
                where order_id = :order and ended_at is null
                returning id, order_id, driver_id, accepted_at, picked_up_at, ended_at, end_reason,
                          engaged_seconds, paid_km, pay_cents, location_lost
                """)
                .param("order", orderId).param("reason", reason.name()).param("road", ROAD_FACTOR)
                .param("hourly", s.driverHourlyCents()).param("perKm", s.driverPerKmCents())
                .query((rs, row) -> new Engagement(rs.getLong("id"), rs.getLong("order_id"), rs.getLong("driver_id"),
                        rs.getTimestamp("accepted_at").toInstant(),
                        rs.getTimestamp("picked_up_at") == null ? null : rs.getTimestamp("picked_up_at").toInstant(),
                        rs.getTimestamp("ended_at").toInstant(), rs.getString("end_reason"),
                        rs.getInt("engaged_seconds"), rs.getDouble("paid_km"), rs.getInt("pay_cents"),
                        rs.getBoolean("location_lost")))
                .optional();
        ended.ifPresent(e -> {
            if (reason == EndReason.DELIVERED && e.pickedUpAt() != null
                    && e.endedAt().isAfter(e.pickedUpAt().plusSeconds(60L * s.lateDeliveryMinutes()))) {
                long minutes = (e.endedAt().getEpochSecond() - e.pickedUpAt().getEpochSecond()) / 60;
                recordViolation(e.driverId(), orderId, ViolationType.LATE_DELIVERY,
                        minutes + " min from pickup to door", s);
            }
            if (reason == EndReason.RELEASED) {
                recordViolation(e.driverId(), orderId, ViolationType.JOB_RELEASED, "Gave the job back", s);
            }
        });
        return ended;
    }

    /**
     * The driver used up the delivery-code tries on this order: one violation, however many more tries follow.
     * The caller then refuses the request with CodeTriesUsed, which does not roll this back.
     */
    @Transactional
    public void codeTriesUsed(long orderId) {
        jdbc.sql("""
                update driver_engagements set code_tries_used = true
                where order_id = :order and ended_at is null and not code_tries_used
                returning driver_id
                """)
                .param("order", orderId).query(Long.class).optional()
                .ifPresent(driver -> recordViolation(driver, orderId, ViolationType.CODE_TRIES_USED,
                        "Used up the delivery-code tries", settings.current()));
    }

    /** What this driver earned for this order (all their engagements on it). 0 if none ended yet. */
    public int payFor(long orderId, long driverId) {
        return jdbc.sql("select coalesce(sum(pay_cents), 0) from driver_engagements where order_id = :o and driver_id = :d")
                .param("o", orderId).param("d", driverId).query(Integer.class).single();
    }

    /** Pay across all engagements on the order, for admin. */
    public int payFor(long orderId) {
        return jdbc.sql("select coalesce(sum(pay_cents), 0) from driver_engagements where order_id = :o")
                .param("o", orderId).query(Integer.class).single();
    }

    // ----------------------------------------------------------- violations

    /**
     * Records a violation, then raises admin alerts at the limits: a daily one
     * (the business day, in app.business-time-zone) and a 30-day "ban review".
     * Each alert is created once (unique indexes), however many violations follow.
     */
    @Transactional
    public void recordViolation(long driverId, Long orderId, ViolationType type, String detail, Settings.Values s) {
        // One driver's violations are recorded one at a time (a lock on the driver id until this
        // transaction ends), so two at the same moment both count each other and a limit is never missed.
        jdbc.sql("select pg_advisory_xact_lock(:d)").param("d", driverId).query((rs, row) -> 1).single();
        jdbc.sql("""
                insert into driver_violations (driver_id, order_id, type, detail, occurred_at)
                values (:driver, :order, :type, :detail, now())
                """)
                .param("driver", driverId).param("order", orderId).param("type", type.name()).param("detail", detail)
                .update();
        LocalDate today = LocalDate.now(businessZone);
        int todayCount = countSince(driverId, today.atStartOfDay(businessZone).toInstant());
        int monthCount = countSince(driverId, Instant.now().minusSeconds(30L * 24 * 3600));
        if (todayCount >= s.dailyViolationLimit()) {
            jdbc.sql("""
                    insert into driver_alerts (driver_id, kind, day, violation_count)
                    values (:driver, 'DAILY_LIMIT', :day, :count) on conflict do nothing
                    """)
                    .param("driver", driverId).param("day", today).param("count", todayCount).update();
        }
        if (monthCount >= s.monthlyViolationLimit()) {
            jdbc.sql("""
                    insert into driver_alerts (driver_id, kind, violation_count)
                    values (:driver, 'BAN_REVIEW', :count) on conflict do nothing
                    """)
                    .param("driver", driverId).param("count", monthCount).update();
        }
    }

    /** A customer said the order never came: always an admin alert, plus a violation. */
    @Transactional
    public void notReceived(long driverId, long orderId) {
        Settings.Values s = settings.current();
        recordViolation(driverId, orderId, ViolationType.NOT_RECEIVED, "Customer says the order never arrived", s);
        jdbc.sql("""
                insert into driver_alerts (driver_id, order_id, kind, violation_count)
                values (:driver, :order, 'NOT_RECEIVED', :count)
                """)
                .param("driver", driverId).param("order", orderId)
                .param("count", countSince(driverId, Instant.now().minusSeconds(30L * 24 * 3600))).update();
    }

    public int countSince(long driverId, Instant since) {
        return jdbc.sql("select count(*) from driver_violations where driver_id = :d and occurred_at >= :since")
                .param("d", driverId).param("since", java.sql.Timestamp.from(since)).query(Integer.class).single();
    }

    /**
     * Every minute: drivers holding an order whose location has not updated for
     * location_lost_minutes (phone died, no signal, GPS off) get one violation
     * per order. Safe on several servers: the flag flips once.
     */
    @Scheduled(fixedDelayString = "${app.dispatch.location-check-ms}")
    @Transactional
    public void flagLostLocations() {
        Settings.Values s = settings.current();
        List<long[]> lost = jdbc.sql("""
                update driver_engagements e set location_lost = true
                from drivers d
                where d.user_id = e.driver_id and e.ended_at is null and not e.location_lost
                  and e.accepted_at < now() - make_interval(mins => :minutes)
                  and (d.located_at is null or d.located_at < now() - make_interval(mins => :minutes))
                returning e.driver_id, e.order_id
                """)
                .param("minutes", s.locationLostMinutes())
                .query((rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)}).list();
        for (long[] row : lost) {
            recordViolation(row[0], row[1], ViolationType.LOCATION_LOST,
                    "No location for over " + s.locationLostMinutes() + " min while holding the order", s);
        }
    }
}
