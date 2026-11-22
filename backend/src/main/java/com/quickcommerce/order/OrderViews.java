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

    public record CustomerOrderView(
            Long id,
            Long shopId,
            String shopName,
            Order.Status status,
            List<Line> items,
            int subtotalCents,
            int deliveryFeeCents,
            int totalCents,
            DeliveryAddress address,
            String note,
            Instant createdAt,
            boolean driverAssigned,
            String driverName,
            String deliveryCode,
            String cancelReason) {}

    /** payoutCents = what the shop earns for the items it can supply. */
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

    /** Everything about an order, for admin only. driver is null until one is assigned. */
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
            int totalCents,
            int shopPayoutCents,
            int driverPayCents,
            DeliveryAddress address,
            String note,
            String cancelReason) {}

    private OrderViews() {}

    // ponytail: no sales tax in the totals yet. Rates and which items are taxed
    // depend on the launch province, which is still undecided.
    static CustomerOrderView customer(Order o, String shopName, List<OrderItem> items, String driverName) {
        int subtotal = items.stream().filter(i -> !i.isUnavailable())
                .mapToInt(i -> i.getQuantity() * i.getCustomerPriceCents()).sum();
        return new CustomerOrderView(o.getId(), o.getShopId(), shopName, o.getStatus(),
                items.stream().map(i -> line(i, i.getCustomerPriceCents())).toList(),
                subtotal, o.getDeliveryFeeCents(), subtotal + o.getDeliveryFeeCents(),
                new DeliveryAddress(o.getAddressLine(), o.getCity(), o.getProvince(), o.getPostalCode(),
                        o.getLatitude(), o.getLongitude()),
                o.getNote(), o.getCreatedAt(), o.getDriverId() != null, driverName, o.getDeliveryCode(), o.getCancelReason());
    }

    static ShopOrderView shop(Order o, List<OrderItem> items) {
        int payout = items.stream().filter(i -> !i.isUnavailable())
                .mapToInt(i -> i.getQuantity() * i.getShopPriceCents()).sum();
        return new ShopOrderView(o.getId(), o.getStatus(),
                items.stream().map(i -> line(i, i.getShopPriceCents())).toList(),
                payout, o.getNote(), o.getCreatedAt(), o.getDriverId() != null, o.getCancelReason());
    }

    static AdminOrderView admin(Order o, String shopName, Map<Long, String> userNames, List<OrderItem> items) {
        List<OrderItem> supplied = items.stream().filter(i -> !i.isUnavailable()).toList();
        int subtotal = supplied.stream().mapToInt(i -> i.getQuantity() * i.getCustomerPriceCents()).sum();
        int payout = supplied.stream().mapToInt(i -> i.getQuantity() * i.getShopPriceCents()).sum();
        return new AdminOrderView(o.getId(), o.getStatus(), o.getCreatedAt(), o.getDeliveredAt(),
                new Party(o.getShopId(), shopName),
                new Party(o.getCustomerId(), userNames.get(o.getCustomerId())),
                o.getDriverId() == null ? null : new Party(o.getDriverId(), userNames.get(o.getDriverId())),
                items.stream().map(i -> new AdminLine(i.getId(), i.getProductId(), i.getName(), i.getQuantity(),
                        i.getShopPriceCents(), i.getCustomerPriceCents(), i.isUnavailable())).toList(),
                subtotal, o.getDeliveryFeeCents(), subtotal + o.getDeliveryFeeCents(), payout, o.getDriverPayCents(),
                new DeliveryAddress(o.getAddressLine(), o.getCity(), o.getProvince(), o.getPostalCode(),
                        o.getLatitude(), o.getLongitude()),
                o.getNote(), o.getCancelReason());
    }

    private static Line line(OrderItem i, int unitPriceCents) {
        return new Line(i.getId(), i.getProductId(), i.getName(), i.getQuantity(), unitPriceCents, i.isUnavailable());
    }
}
