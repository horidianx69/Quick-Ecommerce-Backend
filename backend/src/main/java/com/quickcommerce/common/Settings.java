package com.quickcommerce.common;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The platform's numbers that admin can change at any time (PUT /api/admin/settings):
 * prices, fees, driver pay and violation rules. Stored in the single row of
 * platform_settings. Orders freeze what they use when placed, so a change never
 * alters a past order.
 */
// ponytail: read from the database on every call (one small query). Cache for a
// few seconds if it ever shows up in load tests.
@Component
public class Settings {

    public record Values(
            int shopCutPercent,
            int defaultMarkupPercent,
            int deliveryFeeLowCents,
            int deliveryFeeMidCents,
            int deliveryMidFromCents,
            int freeDeliveryFromCents,
            int handlingFeeCents,
            int smallCartBelowCents,
            int smallCartFeeCents,
            int lateNightFeeCents,
            LocalTime lateNightStart,
            LocalTime lateNightEnd,
            String surgeMode,
            int surgeFeeCents,
            int surgeRatioPercent,
            int driverHourlyCents,
            int driverPerKmCents,
            List<Integer> tipOptionsCents,
            int lateDeliveryMinutes,
            int locationLostMinutes,
            int dailyViolationLimit,
            int monthlyViolationLimit) {

        /** What the shop is paid for one unit listed at this price: the price minus our cut, rounded down to the cent. */
        public int payout(int listedCents) {
            return (int) ((long) listedCents * (100 - shopCutPercent) / 100);
        }

        /** By the items subtotal: below the middle tier, the middle tier, or free. */
        public int deliveryFee(int subtotalCents) {
            if (subtotalCents >= freeDeliveryFromCents) {
                return 0;
            }
            return subtotalCents >= deliveryMidFromCents ? deliveryFeeMidCents : deliveryFeeLowCents;
        }

        public int smallCartFee(int subtotalCents) {
            return subtotalCents < smallCartBelowCents ? smallCartFeeCents : 0;
        }

        /** True inside the late-night window. A window whose start is after its end runs past midnight. */
        public boolean isLateNight(LocalTime time) {
            if (lateNightStart.equals(lateNightEnd)) {
                return false;
            }
            return lateNightStart.isBefore(lateNightEnd)
                    ? !time.isBefore(lateNightStart) && time.isBefore(lateNightEnd)
                    : !time.isBefore(lateNightStart) || time.isBefore(lateNightEnd);
        }
    }

    private static final String COLUMNS = """
            shop_cut_percent, default_markup_percent, delivery_fee_low_cents, delivery_fee_mid_cents,
            delivery_mid_from_cents, free_delivery_from_cents, handling_fee_cents, small_cart_below_cents,
            small_cart_fee_cents, late_night_fee_cents, late_night_start, late_night_end, surge_mode, surge_fee_cents,
            surge_ratio_percent, driver_hourly_cents, driver_per_km_cents, tip_options_cents, late_delivery_minutes,
            location_lost_minutes, daily_violation_limit, monthly_violation_limit""";

    private final JdbcClient jdbc;

    Settings(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public Values current() {
        return read("select " + COLUMNS + " from platform_settings where id = 1");
    }

    /**
     * Replaces the settings in one locked step, so two admins saving at the same
     * moment cannot undo each other's changes. change gets the current values
     * and returns the new ones.
     */
    @Transactional
    public Values update(java.util.function.UnaryOperator<Values> change) {
        Values next = change.apply(read("select " + COLUMNS + " from platform_settings where id = 1 for update"));
        validate(next);
        jdbc.sql("""
                update platform_settings set
                  shop_cut_percent = :shopCut, default_markup_percent = :markup,
                  delivery_fee_low_cents = :feeLow, delivery_fee_mid_cents = :feeMid,
                  delivery_mid_from_cents = :midFrom, free_delivery_from_cents = :freeFrom,
                  handling_fee_cents = :handling, small_cart_below_cents = :smallBelow, small_cart_fee_cents = :smallFee,
                  late_night_fee_cents = :lateFee, late_night_start = :lateStart, late_night_end = :lateEnd,
                  surge_mode = :surgeMode, surge_fee_cents = :surgeFee, surge_ratio_percent = :surgeRatio,
                  driver_hourly_cents = :hourly, driver_per_km_cents = :perKm, tip_options_cents = :tips,
                  late_delivery_minutes = :lateMinutes, location_lost_minutes = :lostMinutes,
                  daily_violation_limit = :daily, monthly_violation_limit = :monthly, updated_at = now()
                where id = 1
                """)
                .param("shopCut", next.shopCutPercent())
                .param("markup", next.defaultMarkupPercent())
                .param("feeLow", next.deliveryFeeLowCents())
                .param("feeMid", next.deliveryFeeMidCents())
                .param("midFrom", next.deliveryMidFromCents())
                .param("freeFrom", next.freeDeliveryFromCents())
                .param("handling", next.handlingFeeCents())
                .param("smallBelow", next.smallCartBelowCents())
                .param("smallFee", next.smallCartFeeCents())
                .param("lateFee", next.lateNightFeeCents())
                .param("lateStart", next.lateNightStart())
                .param("lateEnd", next.lateNightEnd())
                .param("surgeMode", next.surgeMode())
                .param("surgeFee", next.surgeFeeCents())
                .param("surgeRatio", next.surgeRatioPercent())
                .param("hourly", next.driverHourlyCents())
                .param("perKm", next.driverPerKmCents())
                .param("tips", String.join(",", next.tipOptionsCents().stream().map(String::valueOf).toList()))
                .param("lateMinutes", next.lateDeliveryMinutes())
                .param("lostMinutes", next.locationLostMinutes())
                .param("daily", next.dailyViolationLimit())
                .param("monthly", next.monthlyViolationLimit())
                .update();
        return next;
    }

    /** Rules that involve more than one value. Single-value ranges are checked by the database and the request. */
    private static void validate(Values v) {
        if (v.deliveryMidFromCents() > v.freeDeliveryFromCents()) {
            throw bad("deliveryMidFromCents must not be above freeDeliveryFromCents");
        }
        if (v.dailyViolationLimit() > v.monthlyViolationLimit()) {
            throw bad("dailyViolationLimit must not be above monthlyViolationLimit");
        }
        if (v.tipOptionsCents().isEmpty() || v.tipOptionsCents().size() > 6
                || v.tipOptionsCents().stream().anyMatch(t -> t < 1 || t > 10_000)) {
            throw bad("tipOptionsCents must be 1 to 6 amounts between 1 and 10000");
        }
        if (!List.of("AUTO", "ON", "OFF").contains(v.surgeMode())) {
            throw bad("surgeMode must be AUTO, ON or OFF");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private Values read(String sql) {
        return jdbc.sql(sql).query((rs, row) -> new Values(
                rs.getInt("shop_cut_percent"), rs.getInt("default_markup_percent"),
                rs.getInt("delivery_fee_low_cents"), rs.getInt("delivery_fee_mid_cents"),
                rs.getInt("delivery_mid_from_cents"), rs.getInt("free_delivery_from_cents"),
                rs.getInt("handling_fee_cents"), rs.getInt("small_cart_below_cents"), rs.getInt("small_cart_fee_cents"),
                rs.getInt("late_night_fee_cents"),
                rs.getObject("late_night_start", LocalTime.class), rs.getObject("late_night_end", LocalTime.class),
                rs.getString("surge_mode"), rs.getInt("surge_fee_cents"), rs.getInt("surge_ratio_percent"),
                rs.getInt("driver_hourly_cents"), rs.getInt("driver_per_km_cents"),
                Arrays.stream(rs.getString("tip_options_cents").split(",")).map(String::trim).map(Integer::valueOf).toList(),
                rs.getInt("late_delivery_minutes"), rs.getInt("location_lost_minutes"),
                rs.getInt("daily_violation_limit"), rs.getInt("monthly_violation_limit")))
                .single();
    }
}
