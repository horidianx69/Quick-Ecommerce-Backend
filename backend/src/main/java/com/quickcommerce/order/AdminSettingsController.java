package com.quickcommerce.order;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.quickcommerce.common.Settings;
import com.quickcommerce.order.Pricing.CategoryMarkup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin's settings: prices, fees, driver pay and violation rules. A change
 * applies to orders placed after it; orders already placed keep the prices and
 * fees they were placed with.
 */
@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingsController {

    /** Every setting, plus the per-category markups. */
    public record SettingsView(@JsonUnwrapped Settings.Values settings, List<CategoryMarkup> categoryMarkups) {}

    /**
     * Send only the fields to change; the rest stay as they are.
     * Percent fields: 150 = 1.5x. Times are the shop's local time ("23:00").
     */
    public record SettingsRequest(
            @Min(0) @Max(90) Integer shopCutPercent,
            @Min(100) @Max(1000) Integer defaultMarkupPercent,
            @Min(0) @Max(10_000) Integer deliveryFeeLowCents,
            @Min(0) @Max(10_000) Integer deliveryFeeMidCents,
            @Min(0) @Max(1_000_000) Integer deliveryMidFromCents,
            @Min(0) @Max(1_000_000) Integer freeDeliveryFromCents,
            @Min(0) @Max(10_000) Integer handlingFeeCents,
            @Min(0) @Max(1_000_000) Integer smallCartBelowCents,
            @Min(0) @Max(10_000) Integer smallCartFeeCents,
            @Min(0) @Max(10_000) Integer lateNightFeeCents,
            LocalTime lateNightStart,
            LocalTime lateNightEnd,
            @Pattern(regexp = "AUTO|ON|OFF", message = "must be AUTO, ON or OFF") String surgeMode,
            @Min(0) @Max(10_000) Integer surgeFeeCents,
            @Min(100) @Max(1000) Integer surgeRatioPercent,
            @Min(0) @Max(20_000) Integer driverHourlyCents,
            @Min(0) @Max(500) Integer driverPerKmCents,
            @Size(min = 1, max = 6) List<@NotNull @Min(1) @Max(10_000) Integer> tipOptionsCents,
            @Min(5) @Max(240) Integer lateDeliveryMinutes,
            @Min(2) @Max(240) Integer locationLostMinutes,
            @Min(1) @Max(100) Integer dailyViolationLimit,
            @Min(1) @Max(1000) Integer monthlyViolationLimit) {}

    public record MarkupRequest(@NotNull @Min(100) @Max(1000) Integer markupPercent) {}

    private final Pricing pricing;
    private final Settings settings;

    AdminSettingsController(Pricing pricing, Settings settings) {
        this.pricing = pricing;
        this.settings = settings;
    }

    @GetMapping
    SettingsView get() {
        return new SettingsView(settings.current(), pricing.categoryMarkups());
    }

    @PutMapping
    SettingsView update(@Valid @RequestBody SettingsRequest r) {
        settings.update(c -> new Settings.Values(
                or(r.shopCutPercent(), c.shopCutPercent()),
                or(r.defaultMarkupPercent(), c.defaultMarkupPercent()),
                or(r.deliveryFeeLowCents(), c.deliveryFeeLowCents()),
                or(r.deliveryFeeMidCents(), c.deliveryFeeMidCents()),
                or(r.deliveryMidFromCents(), c.deliveryMidFromCents()),
                or(r.freeDeliveryFromCents(), c.freeDeliveryFromCents()),
                or(r.handlingFeeCents(), c.handlingFeeCents()),
                or(r.smallCartBelowCents(), c.smallCartBelowCents()),
                or(r.smallCartFeeCents(), c.smallCartFeeCents()),
                or(r.lateNightFeeCents(), c.lateNightFeeCents()),
                or(r.lateNightStart(), c.lateNightStart()),
                or(r.lateNightEnd(), c.lateNightEnd()),
                or(r.surgeMode(), c.surgeMode()),
                or(r.surgeFeeCents(), c.surgeFeeCents()),
                or(r.surgeRatioPercent(), c.surgeRatioPercent()),
                or(r.driverHourlyCents(), c.driverHourlyCents()),
                or(r.driverPerKmCents(), c.driverPerKmCents()),
                r.tipOptionsCents() == null ? c.tipOptionsCents() : r.tipOptionsCents().stream().sorted().distinct().toList(),
                or(r.lateDeliveryMinutes(), c.lateDeliveryMinutes()),
                or(r.locationLostMinutes(), c.locationLostMinutes()),
                or(r.dailyViolationLimit(), c.dailyViolationLimit()),
                or(r.monthlyViolationLimit(), c.monthlyViolationLimit())));
        return get();
    }

    private static <T> T or(T given, T current) {
        return given != null ? given : current;
    }

    /** Category names match the catalog's exactly, as GET /api/catalog/categories lists them. */
    @PutMapping("/markups/{category}")
    SettingsView setMarkup(@PathVariable String category, @Valid @RequestBody MarkupRequest req) {
        pricing.setCategoryMarkup(requireCategory(category), req.markupPercent());
        return get();
    }

    /** The category goes back to the default markup. */
    @DeleteMapping("/markups/{category}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeMarkup(@PathVariable String category) {
        if (!pricing.removeCategoryMarkup(requireCategory(category))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No markup set for this category");
        }
    }

    private static String requireCategory(String category) {
        String trimmed = category.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "category must be 1 to 80 characters");
        }
        return trimmed;
    }
}
