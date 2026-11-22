package com.quickcommerce.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** One customer order to one shop. Written once; after that only status changes. */
@Entity
@Table(name = "orders")
@Getter
@Setter
public class Order {

    /**
     * PLACED -> ACCEPTED -> READY -> PICKED_UP -> DELIVERED is the happy path.
     * From PLACED an order can also end as REJECTED (shop said no), CANCELLED
     * (customer changed their mind) or EXPIRED (shop never answered).
     * A driver takes the order while it is ACCEPTED or READY, and sets PICKED_UP and DELIVERED.
     */
    public enum Status { PLACED, ACCEPTED, READY, PICKED_UP, DELIVERED, REJECTED, CANCELLED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long customerId;
    private Long shopId;
    private String idempotencyKey;

    /** Changed only by OrderRepository.move, one atomic step per change. Saving this entity never writes it. */
    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private Status status;

    private String addressLine;
    private String city;
    private String province;
    private String postalCode;
    private double latitude;
    private double longitude;
    private String note;
    /** Fees, frozen when the order is placed. */
    private int deliveryFeeCents;
    private int handlingFeeCents;
    private int smallCartFeeCents;
    private int lateNightFeeCents;
    private int surgeFeeCents;
    /** Set at checkout or afterwards (OrderRepository.tip). Goes to the driver in full. */
    @Column(updatable = false)
    private int tipCents;
    /** null, ASKED, RECEIVED or NOT_RECEIVED. Changed only by OrderRepository. */
    @Column(updatable = false)
    private String receiptStatus;
    @Column(updatable = false)
    private Instant createdAt;

    /** The driver carrying this order, once one has accepted it. Changed only by OrderRepository. */
    @Column(updatable = false)
    private Long driverId;
    /** Four digits. The customer reads it to the driver at the door; the driver needs it to mark the order delivered. */
    private String deliveryCode;
    @Column(updatable = false)
    private Instant deliveredAt;
    /** Why admin cancelled the order. Null when the customer cancelled, or the order was not cancelled. */
    @Column(updatable = false)
    private String cancelReason;

    /** Every fee, without the tip. */
    public int feesCents() {
        return deliveryFeeCents + handlingFeeCents + smallCartFeeCents + lateNightFeeCents + surgeFeeCents;
    }
}
