package com.quickcommerce.order;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The prices and fees admin sets (see AdminSettingsController), and the one
 * place that turns a shop's price into the customer's price. Browsing and
 * ordering both use it, so the price a customer sees is the price charged.
 */
// ponytail: read from the database on every call (two small queries). Cache
// for a few seconds if it ever shows up in load tests.
@Component
public class Pricing {

    public record CategoryMarkup(String category, int markupPercent) {}

    /** One consistent snapshot of the settings. Take it once per request and use it for every line. */
    public record Prices(int defaultMarkupPercent, Map<String, Integer> categoryMarkups,
            int deliveryFeeCents, int driverPayCents) {

        /** Shop price plus the markup for the product's category, rounded to the nearest cent. */
        public int customerPrice(int shopPriceCents, String category) {
            int markup = categoryMarkups.getOrDefault(category, defaultMarkupPercent);
            return (int) ((shopPriceCents * (long) markup + 50) / 100);
        }
    }

    private final JdbcClient jdbc;

    Pricing(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public Prices current() {
        Map<String, Integer> markups = categoryMarkups().stream()
                .collect(Collectors.toMap(CategoryMarkup::category, CategoryMarkup::markupPercent));
        return jdbc.sql("select default_markup_percent, delivery_fee_cents, driver_pay_cents from platform_settings where id = 1")
                .query((rs, row) -> new Prices(rs.getInt(1), markups, rs.getInt(2), rs.getInt(3)))
                .single();
    }

    public List<CategoryMarkup> categoryMarkups() {
        return jdbc.sql("select category, markup_percent from category_markups order by category")
                .query(CategoryMarkup.class)
                .list();
    }

    void update(int defaultMarkupPercent, int deliveryFeeCents, int driverPayCents) {
        jdbc.sql("""
                update platform_settings
                set default_markup_percent = :markup, delivery_fee_cents = :fee, driver_pay_cents = :pay, updated_at = now()
                where id = 1
                """)
                .param("markup", defaultMarkupPercent)
                .param("fee", deliveryFeeCents)
                .param("pay", driverPayCents)
                .update();
    }

    /** Adds or replaces in one statement, so two admins saving at once cannot clash. */
    void setCategoryMarkup(String category, int markupPercent) {
        jdbc.sql("""
                insert into category_markups (category, markup_percent) values (:category, :markup)
                on conflict (category) do update set markup_percent = excluded.markup_percent
                """)
                .param("category", category)
                .param("markup", markupPercent)
                .update();
    }

    boolean removeCategoryMarkup(String category) {
        return jdbc.sql("delete from category_markups where category = :category").param("category", category).update() > 0;
    }
}
