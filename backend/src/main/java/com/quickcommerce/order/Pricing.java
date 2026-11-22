package com.quickcommerce.order;

import com.quickcommerce.common.Settings;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The one place that turns a shop's listed price into what the shop is paid and
 * what the customer pays. Browsing and ordering both use it, so the price a
 * customer sees is the price charged.
 *
 * For a shop price P:
 *   shop is paid   = P x (100 - cut)% rounded down to the cent          (cut: 15%)
 *   customer pays  = P x markup%, rounded DOWN to the nearest .49 or .99 (markup: 150%)
 *                    but never at or below what the shop is paid; then it rounds UP instead.
 */
@Component
public class Pricing {

    public record CategoryMarkup(String category, int markupPercent) {}

    /** One consistent snapshot of the settings. Take it once per request and use it for every line. */
    public record Prices(Settings.Values settings, Map<String, Integer> categoryMarkups) {

        public int payout(int listedCents) {
            return settings.payout(listedCents);
        }

        public int customerPrice(int listedCents, String category) {
            int markup = categoryMarkups.getOrDefault(category, settings.defaultMarkupPercent());
            long rawTimes100 = (long) listedCents * markup; // the exact price in hundredths of a cent
            int payout = payout(listedCents);
            int price = charmAtOrBelow((int) (rawTimes100 / 100));
            if (price <= payout) {
                // Rounding down would sell at a loss (only for very cheap items): round up instead.
                price = charmAtOrAbove((int) ((rawTimes100 + 99) / 100));
                while (price <= payout) {
                    price = charmAtOrAbove(price + 1);
                }
            }
            return price;
        }
    }

    /** The largest price ending in .49 or .99 that is not above cents. Can be -1 for prices under 49 cents. */
    static int charmAtOrBelow(int cents) {
        int base = cents - cents % 100;
        int rest = cents % 100;
        return rest >= 99 ? base + 99 : rest >= 49 ? base + 49 : base - 1;
    }

    /** The smallest price ending in .49 or .99 that is not below cents. */
    static int charmAtOrAbove(int cents) {
        int base = cents - cents % 100;
        int rest = cents % 100;
        return rest <= 49 ? base + 49 : base + 99;
    }

    private final JdbcClient jdbc;
    private final Settings settings;

    Pricing(DataSource dataSource, Settings settings) {
        this.jdbc = JdbcClient.create(dataSource);
        this.settings = settings;
    }

    public Prices current() {
        Map<String, Integer> markups = categoryMarkups().stream()
                .collect(Collectors.toMap(CategoryMarkup::category, CategoryMarkup::markupPercent));
        return new Prices(settings.current(), markups);
    }

    public List<CategoryMarkup> categoryMarkups() {
        return jdbc.sql("select category, markup_percent from category_markups order by category")
                .query(CategoryMarkup.class)
                .list();
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
