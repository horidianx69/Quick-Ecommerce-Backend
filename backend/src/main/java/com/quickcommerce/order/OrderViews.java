package com.quickcommerce.order;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The two faces of one order. Each side sees only its own money:
 * the customer never sees the shop's price or the shop's address,
 * the shop never sees the customer's price or the delivery address.
 * deliveryCode is for the customer alone: they read it to the driver at the door.
 */
public final class OrderViews {

    public record Line(Long id, Long productId, String name, int quantity, int unitPriceCents, boolean unavailable) {}

    /**
     * totalCents = subtotalCents + every fee + tipCents. Fees are frozen when the
     * order is placed; items the shop could not supply are left out of the subtotal.
     * receiptStatus: null, or ASKED when the app should ask "Did you receive your order?",
     * then RECEIVED or NOT_RECEIVED once answered.
     */
    public record CustomerOrderView(
            Long id,
            Long shopId,
            String shopName,
            Order.Status status,
            List<Line> items,
            int subtotalCents,
            int deliveryFeeCents,
            int handlingFeeCents,
            int smallCartFeeCents,
            int lateNightFeeCents,
            int surgeFeeCents,
            int tipCents,
            int totalCents,
            DeliveryAddress address,
            String note,
            Instant createdAt,
            Instant deliveredAt,
            boolean driverAssigned,
            String driverName,
            String deliveryCode,
            String cancelReason,
            String receiptStatus) {}

    /** payoutCents = what the shop earns for the items it can supply. unitPriceCents is the shop's payout per unit. */
    public record ShopOrderView(
            Long id,
            Order.Status status,
            List<Line> items,
            int payoutCents,
            String note,
            Instant createdAt,
            boolean driverAssigned,
            String cancelReason) {}

    /** A shop, customer or driver as admin sees them on an order. */
    public record Party(Long id, String name) {}

    /** Both prices per line: what the shop earns and what the customer pays. */
    public record AdminLine(Long id, Long productId, String name, int quantity, int shopPriceCents,
            int customerPriceCents, boolean unavailable) {}

    /**
     * Everything about an order, for admin only. driver is null until one is assigned.
     * itemMarginCents = subtotal - shop payout. driverPayCents = engaged-time pay so far.
     * platformNetCents = item margin + all fees - driver pay (tips go to the driver and are left out).
     */
    public record AdminOrderView(
            Long id,
            Order.Status status,
            Instant createdAt,
            Instant deliveredAt,
            Party shop,
            Party customer,
            Party driver,
            List<AdminLine> items,
            int subtotalCents,
            int deliveryFeeCents,
            int handlingFeeCents,
            int smallCartFeeCents,
            int lateNightFeeCents,
            int surgeFeeCents,
            int tipCents,
            int totalCents,
            int shopPayoutCents,
            int itemMarginCents,
            int driverPayCents,
            int platformNetCents,
            DeliveryAddress address,
            String note,
            String cancelReason,
            String receiptStatus) {}

    private OrderViews() {}

    // ponytail: no sales tax in the totals yet. Rates and which items are taxed
    // depend on the launch province, which is still undecided.
    static CustomerOrderView customer(Order o, String shopName, List<OrderItem> items, String driverName) {
        int subtotal = subtotal(items);
        return new CustomerOrderView(o.getId(), o.getShopId(), shopName, o.getStatus(),
                items.stream().map(i -> line(i, i.getCustomerPriceCents())).toList(),
                subtotal, o.getDeliveryFeeCents(), o.getHandlingFeeCents(), o.getSmallCartFeeCents(),
                o.getLateNightFeeCents(), o.getSurgeFeeCents(), o.getTipCents(),
                subtotal + o.feesCents() + o.getTipCents(),
                address(o), o.getNote(), o.getCreatedAt(), o.getDeliveredAt(), o.getDriverId() != null, driverName,
                o.getDeliveryCode(), o.getCancelReason(), o.getReceiptStatus());
    }

    static ShopOrderView shop(Order o, List<OrderItem> items) {
        return new ShopOrderView(o.getId(), o.getStatus(),
                items.stream().map(i -> line(i, i.getShopPriceCents())).toList(),
                payout(items), o.getNote(), o.getCreatedAt(), o.getDriverId() != null, o.getCancelReason());
    }

    static AdminOrderView admin(Order o, String shopName, Map<Long, String> userNames, List<OrderItem> items,
            int driverPayCents) {
        int subtotal = subtotal(items);
        int payout = payout(items);
        return new AdminOrderView(o.getId(), o.getStatus(), o.getCreatedAt(), o.getDeliveredAt(),
                new Party(o.getShopId(), shopName),
                new Party(o.getCustomerId(), userNames.get(o.getCustomerId())),
                o.getDriverId() == null ? null : new Party(o.getDriverId(), userNames.get(o.getDriverId())),
                items.stream().map(i -> new AdminLine(i.getId(), i.getProductId(), i.getName(), i.getQuantity(),
                        i.getShopPriceCents(), i.getCustomerPriceCents(), i.isUnavailable())).toList(),
                subtotal, o.getDeliveryFeeCents(), o.getHandlingFeeCents(), o.getSmallCartFeeCents(),
                o.getLateNightFeeCents(), o.getSurgeFeeCents(), o.getTipCents(),
                subtotal + o.feesCents() + o.getTipCents(),
                payout, subtotal - payout, driverPayCents, subtotal - payout + o.feesCents() - driverPayCents,
                address(o), o.getNote(), o.getCancelReason(), o.getReceiptStatus());
    }

    private static int subtotal(List<OrderItem> items) {
        return items.stream().filter(i -> !i.isUnavailable()).mapToInt(i -> i.getQuantity() * i.getCustomerPriceCents()).sum();
    }

    private static int payout(List<OrderItem> items) {
        return items.stream().filter(i -> !i.isUnavailable()).mapToInt(i -> i.getQuantity() * i.getShopPriceCents()).sum();
    }

    private static DeliveryAddress address(Order o) {
        return new DeliveryAddress(o.getAddressLine(), o.getCity(), o.getProvince(), o.getPostalCode(),
                o.getLatitude(), o.getLongitude());
    }

    private static Line line(OrderItem i, int unitPriceCents) {
        return new Line(i.getId(), i.getProductId(), i.getName(), i.getQuantity(), unitPriceCents, i.isUnavailable());
    }
}
