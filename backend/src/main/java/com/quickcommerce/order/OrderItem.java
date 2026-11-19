package com.quickcommerce.order;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One line of an order, with the name and both prices frozen at order time. */
@Entity
@Table(name = "order_items")
@Getter
@Setter
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long orderId;
    /** Null once the shop has removed this product from its list. */
    private Long shopProductId;
    private Long productId;
    private String name;
    private int quantity;
    /** What the shop earns per unit. */
    private int shopPriceCents;
    /** What the customer pays per unit (shop price plus platform markup). */
    private int customerPriceCents;
    /** Set by the shop when accepting: it turned out not to have this item. Not charged, not paid out. */
    private boolean unavailable;
}
