package com.quickcommerce.admin;

import com.quickcommerce.common.DatabaseTime;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Daily numbers for admin's charts, one row per day (business time zone), so
 * admin can see the effect of a price or fee change and decide the next one.
 */
// ponytail: computed from the order tables on every call. Fine for a year of a
// pilot's orders; keep a daily summary table when this slows down.
@RestController
public class AdminMetricsController {

    /**
     * Money in cents. Placed and lost (rejected, cancelled, expired) count by the
     * day the order was placed; sales, payouts, fees and tips by the day it was
     * delivered; driver pay by the day each engagement ended.
     * itemMargin = sales - shop payouts. net = item margin + fees - driver pay (tips go to drivers).
     */
    public record Day(LocalDate day, int ordersPlaced, int ordersDelivered, int ordersLost, int surgeOrders,
            long salesCents, long shopPayoutCents, long itemMarginCents, long feesCents, long tipsCents,
            long driverPayCents, long netCents, Double avgDeliveryMinutes, int lateDeliveries, int violations,
            Double avgStars, int reviews) {}

    public record Metrics(LocalDate from, LocalDate to, Day totals, List<Day> days) {}

    private static final String SQL = """
            with days as (
                select cast(generate_series(cast(:from as date), cast(:to as date), interval '1 day') as date) as day
            ), placed as (
                select cast(created_at at time zone :tz as date) as day, count(*) as placed,
                       count(*) filter (where status in ('REJECTED', 'CANCELLED', 'EXPIRED')) as lost,
                       count(*) filter (where surge_fee_cents > 0) as surge
                from orders where created_at >= :fromTs and created_at < :toTs group by 1
            ), delivered as (
                select cast(o.delivered_at at time zone :tz as date) as day, count(*) as delivered,
                       sum(i.sales) as sales, sum(i.payout) as payout,
                       sum(o.delivery_fee_cents + o.handling_fee_cents + o.small_cart_fee_cents
                           + o.late_night_fee_cents + o.surge_fee_cents) as fees,
                       sum(o.tip_cents) as tips
                from orders o
                cross join lateral (
                    select coalesce(sum(quantity * customer_price_cents) filter (where not unavailable), 0) as sales,
                           coalesce(sum(quantity * shop_price_cents) filter (where not unavailable), 0) as payout
                    from order_items where order_id = o.id) i
                where o.status = 'DELIVERED' and o.delivered_at >= :fromTs and o.delivered_at < :toTs group by 1
            ), work as (
                select cast(ended_at at time zone :tz as date) as day, sum(pay_cents) as pay,
                       avg(extract(epoch from ended_at - picked_up_at))
                           filter (where end_reason = 'DELIVERED' and picked_up_at is not null) as avg_secs,
                       count(*) filter (where end_reason = 'DELIVERED'
                           and ended_at - picked_up_at > make_interval(mins => :lateMinutes)) as late
                from driver_engagements where ended_at >= :fromTs and ended_at < :toTs group by 1
            ), viol as (
                select cast(occurred_at at time zone :tz as date) as day, count(*) as n
                from driver_violations where occurred_at >= :fromTs and occurred_at < :toTs group by 1
            ), rev as (
                select cast(created_at at time zone :tz as date) as day, avg(stars) as stars, count(*) as n
                from reviews where created_at >= :fromTs and created_at < :toTs group by 1
            )
            select d.day,
                   coalesce(p.placed, 0) as placed, coalesce(dl.delivered, 0) as delivered, coalesce(p.lost, 0) as lost,
                   coalesce(p.surge, 0) as surge, coalesce(dl.sales, 0) as sales, coalesce(dl.payout, 0) as payout,
                   coalesce(dl.fees, 0) as fees, coalesce(dl.tips, 0) as tips, coalesce(w.pay, 0) as pay,
                   w.avg_secs, coalesce(w.late, 0) as late, coalesce(v.n, 0) as violations,
                   r.stars, coalesce(r.n, 0) as reviews
            from days d
            left join placed p on p.day = d.day
            left join delivered dl on dl.day = d.day
            left join work w on w.day = d.day
            left join viol v on v.day = d.day
            left join rev r on r.day = d.day
            order by d.day
            """;

    private final JdbcClient jdbc;
    private final String zone;
    private final DatabaseTime time;

    AdminMetricsController(DataSource dataSource, DatabaseTime time, @Value("${app.business-time-zone}") String zone) {
        this.jdbc = JdbcClient.create(dataSource);
        this.time = time;
        this.zone = zone;
    }

    /** One row per day from `from` to `to` (inclusive, at most 366 days). Default: the last 30 days. */
    @GetMapping("/api/admin/metrics")
    Metrics metrics(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : time.today();
        LocalDate start = from != null ? from : end.minusDays(29);
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > 365) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be on or before to, at most 366 days apart");
        }
        int lateMinutes = jdbc.sql("select late_delivery_minutes from platform_settings where id = 1").query(Integer.class).single();
        List<Day> days = jdbc.sql(SQL)
                .param("from", start).param("to", end).param("tz", zone)
                .param("fromTs", Timestamp.from(time.dayStart(start)))
                .param("toTs", Timestamp.from(time.dayStart(end.plusDays(1))))
                .param("lateMinutes", lateMinutes)
                .query((rs, row) -> {
                    long sales = rs.getLong("sales");
                    long payout = rs.getLong("payout");
                    long fees = rs.getLong("fees");
                    long pay = rs.getLong("pay");
                    Object secs = rs.getObject("avg_secs");
                    Object stars = rs.getObject("stars");
                    return new Day(rs.getObject("day", LocalDate.class), rs.getInt("placed"), rs.getInt("delivered"),
                            rs.getInt("lost"), rs.getInt("surge"), sales, payout, sales - payout, fees, rs.getLong("tips"),
                            pay, sales - payout + fees - pay,
                            secs == null ? null : Math.round(((Number) secs).doubleValue() / 6) / 10.0,
                            rs.getInt("late"), rs.getInt("violations"),
                            stars == null ? null : Math.round(((Number) stars).doubleValue() * 10) / 10.0, rs.getInt("reviews"));
                })
                .list();
        return new Metrics(start, end, total(days), days);
    }

    /** Sums for the whole range. Averages are weighted by delivered orders and by reviews. */
    private static Day total(List<Day> days) {
        int delivered = days.stream().mapToInt(Day::ordersDelivered).sum();
        int reviews = days.stream().mapToInt(Day::reviews).sum();
        double minutes = days.stream().filter(d -> d.avgDeliveryMinutes() != null)
                .mapToDouble(d -> d.avgDeliveryMinutes() * d.ordersDelivered()).sum();
        double stars = days.stream().filter(d -> d.avgStars() != null).mapToDouble(d -> d.avgStars() * d.reviews()).sum();
        return new Day(null, days.stream().mapToInt(Day::ordersPlaced).sum(), delivered,
                days.stream().mapToInt(Day::ordersLost).sum(), days.stream().mapToInt(Day::surgeOrders).sum(),
                days.stream().mapToLong(Day::salesCents).sum(), days.stream().mapToLong(Day::shopPayoutCents).sum(),
                days.stream().mapToLong(Day::itemMarginCents).sum(), days.stream().mapToLong(Day::feesCents).sum(),
                days.stream().mapToLong(Day::tipsCents).sum(), days.stream().mapToLong(Day::driverPayCents).sum(),
                days.stream().mapToLong(Day::netCents).sum(),
                delivered == 0 ? null : Math.round(minutes / delivered * 10) / 10.0,
                days.stream().mapToInt(Day::lateDeliveries).sum(), days.stream().mapToInt(Day::violations).sum(),
                reviews == 0 ? null : Math.round(stars / reviews * 10) / 10.0, reviews);
    }
}
