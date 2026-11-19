package com.quickcommerce.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place that turns a shop's price into the customer's price. Browsing
 * and ordering both use it, so the price a customer sees is the price charged.
 */
@Component
public class Pricing {

    private final int markupPercent;

    Pricing(@Value("${app.orders.markup-percent}") int markupPercent) {
        this.markupPercent = markupPercent;
    }

    /** Shop price plus the platform markup, rounded to the nearest cent. */
    // ponytail: one markup for everything, from config. A per-category table
    // replaces this when the admin settings screen is built.
    public int customerPrice(int shopPriceCents) {
        return (int) ((shopPriceCents * (long) markupPercent + 50) / 100);
    }
}
