package com.quickcommerce.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A catalog product as sold by one shop: that shop's price and stock count. */
@Entity
@Table(name = "shop_products")
@Getter
@Setter
public class ShopProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long shopId;
    private Long productId;
    /** The shop's own price in CAD cents. Platform markup is applied later, on the customer side. */
    private int priceCents;
    private int quantity;
}
