package com.quickcommerce.catalog;

/** A shop product joined with its catalog details, as the shop's product list shows it. */
public record ShopProductView(
        Long id,
        Long productId,
        String name,
        String brand,
        String imageUrl,
        String category,
        int priceCents,
        int quantity) {}
