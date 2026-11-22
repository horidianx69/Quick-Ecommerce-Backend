package com.quickcommerce.order;

import com.quickcommerce.order.Pricing.CategoryMarkup;
import com.quickcommerce.order.Pricing.Prices;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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
 * Admin's price settings. A change applies to orders placed after it; orders
 * already placed keep the prices, fee and driver pay they were placed with.
 */
@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingsController {

    /** markups are percents: 150 = customer pays 1.5x the shop's price. 100 = no markup. */
    public record SettingsView(int defaultMarkupPercent, int deliveryFeeCents, int driverPayCents,
            List<CategoryMarkup> categoryMarkups) {}

    public record SettingsRequest(
            @NotNull @Min(100) @Max(1000) Integer defaultMarkupPercent,
            @NotNull @Min(0) @Max(10_000) Integer deliveryFeeCents,
            @NotNull @Min(0) @Max(10_000) Integer driverPayCents) {}

    public record MarkupRequest(@NotNull @Min(100) @Max(1000) Integer markupPercent) {}

    private final Pricing pricing;

    AdminSettingsController(Pricing pricing) {
        this.pricing = pricing;
    }

    @GetMapping
    SettingsView get() {
        Prices prices = pricing.current();
        return new SettingsView(prices.defaultMarkupPercent(), prices.deliveryFeeCents(), prices.driverPayCents(),
                pricing.categoryMarkups());
    }

    @PutMapping
    SettingsView update(@Valid @RequestBody SettingsRequest req) {
        pricing.update(req.defaultMarkupPercent(), req.deliveryFeeCents(), req.driverPayCents());
        return get();
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
