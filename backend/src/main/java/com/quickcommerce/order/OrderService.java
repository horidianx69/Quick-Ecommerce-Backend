package com.quickcommerce.order;

import com.quickcommerce.auth.Throttle;
import com.quickcommerce.auth.User;
import com.quickcommerce.auth.UserRepository;
import com.quickcommerce.catalog.ShopProductView;
import com.quickcommerce.catalog.StockService;
import com.quickcommerce.common.Geo;
import com.quickcommerce.common.Paging;
import com.quickcommerce.order.Order.Status;
import com.quickcommerce.order.OrderViews.AdminOrderView;
import com.quickcommerce.order.OrderViews.CustomerOrderView;
import com.quickcommerce.order.OrderViews.ShopOrderView;
import com.quickcommerce.shop.Shop;
import com.quickcommerce.shop.ShopService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Every order rule lives here: placing, pricing, and each status change.
 * Controllers only translate HTTP; nothing else may change an order.
 */
@Service
public class OrderService {

    /**
     * ponytail: until payment exists, an order costs nothing to place, so this
     * cap is the only brake on one customer reserving a shop's whole stock.
     * Revisit when the card hold is in.
     */
    static final int MAX_UNANSWERED_ORDERS_PER_CUSTOMER = 3;

    public record Placed(CustomerOrderView order, boolean created) {}

    /** A driver can take an order from the moment the shop accepts it, and travel while it is packed. */
    static final List<Status> WAITING_FOR_PICKUP = List.of(Status.ACCEPTED, Status.READY);
    static final List<Status> WITH_A_DRIVER = List.of(Status.ACCEPTED, Status.READY, Status.PICKED_UP);
    /** Not finished yet: admin can still cancel these. */
    static final List<Status> ACTIVE = List.of(Status.PLACED, Status.ACCEPTED, Status.READY, Status.PICKED_UP);
    static final int DELIVERY_CODE_TRIES = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final StockService stock;
    private final ShopService shops;
    private final Pricing pricing;
    private final double maxDistanceKm;
    private final Duration acceptTimeout;
    private final Throttle throttle;
    private final UserRepository users;

    OrderService(OrderRepository orders, OrderItemRepository items, StockService stock, ShopService shops,
            Pricing pricing, Throttle throttle, UserRepository users,
            @Value("${app.orders.max-distance-km}") double maxDistanceKm,
            @Value("${app.orders.accept-timeout}") Duration acceptTimeout) {
        this.orders = orders;
        this.items = items;
        this.stock = stock;
        this.shops = shops;
        this.pricing = pricing;
        this.throttle = throttle;
        this.users = users;
        this.maxDistanceKm = maxDistanceKm;
        this.acceptTimeout = acceptTimeout;
    }

    // ------------------------------------------------------------ placing

    /**
     * Takes the stock and writes the order in one transaction: both happen or
     * neither does. The same idempotencyKey from the same customer returns the
     * first order instead of making a second one.
     *
     * @param quantities shop product id -> units
     */
    @Transactional
    public Placed place(Long customerId, Long shopId, String idempotencyKey, Map<Long, Integer> quantities,
            DeliveryAddress address, String note) {
        var replay = orders.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
        if (replay.isPresent()) {
            return new Placed(customerView(replay.get()), false);
        }
        if (orders.countByCustomerIdAndStatus(customerId, Status.PLACED) >= MAX_UNANSWERED_ORDERS_PER_CUSTOMER) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You have orders waiting for a shop to answer. Try again once they are answered.");
        }
        Shop shop = shops.requireApproved(shopId);
        if (!shops.isOpenNow(shopId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This shop is closed right now");
        }
        // Measured from the shop's public point, the same one browsing uses, so the
        // range check tells a customer nothing more about where the shop really is.
        if (Geo.distanceKm(shop.getPublicLatitude(), shop.getPublicLongitude(), address.latitude(), address.longitude())
                > maxDistanceKm) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Address is outside this shop's delivery range");
        }

        List<ShopProductView> reserved = stock.reserve(shopId, quantities);
        Pricing.Prices prices = pricing.current();

        Order order = new Order();
        order.setCustomerId(customerId);
        order.setShopId(shopId);
        order.setIdempotencyKey(idempotencyKey);
        order.setStatus(Status.PLACED);
        order.setAddressLine(address.addressLine().trim());
        order.setCity(address.city().trim());
        order.setProvince(address.province());
        order.setPostalCode(address.postalCode().toUpperCase());
        order.setLatitude(address.latitude());
        order.setLongitude(address.longitude());
        order.setNote(note == null || note.isBlank() ? null : note.trim());
        order.setDeliveryFeeCents(prices.deliveryFeeCents());
        order.setDriverPayCents(prices.driverPayCents());
        order.setCreatedAt(Instant.now());
        order.setDeliveryCode("%04d".formatted(RANDOM.nextInt(10_000)));
        orders.save(order);

        List<OrderItem> lines = new ArrayList<>();
        for (ShopProductView product : reserved) {
            OrderItem line = new OrderItem();
            line.setOrderId(order.getId());
            line.setShopProductId(product.id());
            line.setProductId(product.productId());
            line.setName(product.name());
            line.setQuantity(quantities.get(product.id()));
            line.setShopPriceCents(product.priceCents());
            line.setCustomerPriceCents(prices.customerPrice(product.priceCents(), product.category()));
            lines.add(line);
        }
        items.saveAll(lines);
        // ponytail: the card hold goes here when the payment part exists.
        return new Placed(customerView(order), true);
    }

    // ----------------------------------------------------- status changes

    /** Customer changes their mind. Only possible while the shop has not answered. */
    @Transactional
    public CustomerOrderView cancel(Long customerId, Long orderId) {
        Order order = forCustomer(customerId, orderId);
        if (orders.cancel(orderId, Status.PLACED, Status.CANCELLED, null) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not PLACED any more");
        }
        restoreStock(order);
        return customerView(forCustomer(customerId, orderId));
    }

    /**
     * Shop takes the order. Items it turns out not to have are marked
     * unavailable (not charged, not paid out) and that product's stock is set
     * to zero, because the count on file was wrong.
     */
    @Transactional
    public ShopOrderView accept(Long shopId, Long orderId, Set<Long> unavailableItemIds) {
        forShop(shopId, orderId);
        List<OrderItem> lines = items.findByOrderIdInOrderById(List.of(orderId));
        Set<Long> lineIds = lines.stream().map(OrderItem::getId).collect(Collectors.toSet());
        if (!lineIds.containsAll(unavailableItemIds)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unavailableItemIds has an item that is not in this order");
        }
        if (unavailableItemIds.size() == lines.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every item is unavailable: reject the order instead");
        }
        move(orderId, Status.PLACED, Status.ACCEPTED);
        if (!unavailableItemIds.isEmpty()) {
            items.markUnavailable(orderId, unavailableItemIds);
            lines.stream()
                    .filter(line -> unavailableItemIds.contains(line.getId()) && line.getShopProductId() != null)
                    .forEach(line -> stock.markOutOfStock(shopId, line.getShopProductId()));
        }
        // ponytail: the card is charged here (for available items only) when the payment part exists.
        return shopView(forShop(shopId, orderId));
    }

    /** Shop says no. The stock it held goes back on the shelf. */
    @Transactional
    public ShopOrderView reject(Long shopId, Long orderId) {
        Order order = forShop(shopId, orderId);
        move(orderId, Status.PLACED, Status.REJECTED);
        restoreStock(order);
        return shopView(forShop(shopId, orderId));
    }

    /** Shop has packed the order; it is waiting for a driver. */
    @Transactional
    public ShopOrderView ready(Long shopId, Long orderId) {
        forShop(shopId, orderId);
        move(orderId, Status.ACCEPTED, Status.READY);
        return shopView(forShop(shopId, orderId));
    }

    // ------------------------------------------------------- driver steps
    // Called by the dispatch part, which decides WHO may take a job. The rules
    // for WHEN each step is allowed stay here with the other status rules.

    /** The order, if it is still waiting for a driver. */
    @Transactional(readOnly = true)
    public Optional<Order> findOpenJob(Long orderId) {
        return orders.findById(orderId)
                .filter(o -> o.getDriverId() == null && WAITING_FOR_PICKUP.contains(o.getStatus()));
    }

    /** The one order this driver is carrying or on the way to collect. */
    @Transactional(readOnly = true)
    public Optional<Order> findActiveDelivery(Long driverId) {
        return orders.findFirstByDriverIdAndStatusIn(driverId, WITH_A_DRIVER);
    }

    /**
     * Gives the job to the driver. Of many drivers accepting at once exactly
     * one wins. A driver carries one order at a time: the check below gives
     * the friendly message, and a unique index in the database is the real
     * guard when one driver taps accept on two jobs at the same moment.
     */
    @Transactional
    public void assignDriver(Long orderId, Long driverId) {
        if (orders.existsByDriverIdAndStatusIn(driverId, WITH_A_DRIVER)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Finish your current delivery first");
        }
        if (orders.assignDriver(orderId, driverId, WAITING_FOR_PICKUP) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This job has been taken");
        }
    }

    /** The driver gives the job back before pickup; it returns to the pool for other drivers. */
    @Transactional
    public void releaseDriver(Long driverId) {
        Order order = requireActiveDelivery(driverId);
        if (orders.releaseDriver(order.getId(), driverId, WAITING_FOR_PICKUP) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Too late: the order is already picked up");
        }
    }

    @Transactional
    public void pickUp(Long driverId) {
        Order order = requireActiveDelivery(driverId);
        if (orders.moveForDriver(order.getId(), driverId, Status.READY, Status.PICKED_UP) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, order.getStatus() == Status.ACCEPTED
                    ? "The shop has not marked this order ready yet"
                    : "This order is already picked up");
        }
    }

    /**
     * Ends the delivery. The driver must give the code only the customer has,
     * so an order cannot be marked delivered from somewhere else. Returns the order id.
     */
    @Transactional
    public Long deliver(Long driverId, String code) {
        Order order = requireActiveDelivery(driverId);
        if (order.getStatus() != Status.PICKED_UP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pick the order up first");
        }
        // Counted before checking, so parallel guesses cannot beat the limit: 5 tries at 10,000 codes.
        if (!throttle.tryAcquire("deliver:" + order.getId(), DELIVERY_CODE_TRIES, Duration.ofMinutes(15), Instant.now())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many wrong codes. Contact support.");
        }
        boolean matches = MessageDigest.isEqual(
                order.getDeliveryCode().getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Wrong delivery code");
        }
        if (orders.deliver(order.getId(), driverId, Instant.now(), Status.PICKED_UP, Status.DELIVERED) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is already delivered");
        }
        // ponytail: the shop payout and driver pay are released here when the payment part exists.
        return order.getId();
    }

    /** How many units the driver carries: a count only, never what the items are. */
    @Transactional(readOnly = true)
    public int unitCount(Long orderId) {
        return items.findByOrderIdInOrderById(List.of(orderId)).stream()
                .filter(line -> !line.isUnavailable()).mapToInt(OrderItem::getQuantity).sum();
    }

    @Transactional(readOnly = true)
    public Page<Order> deliveredBy(Long driverId, int page, int size) {
        return orders.findByDriverIdAndStatus(driverId, Status.DELIVERED, newestFirst(page, size));
    }

    /** The customer's own order as stored, for the tracking view. */
    @Transactional(readOnly = true)
    public Order requireForCustomer(Long customerId, Long orderId) {
        return forCustomer(customerId, orderId);
    }

    @Transactional(readOnly = true)
    public Order require(Long orderId) {
        return orders.findById(orderId).orElseThrow(OrderService::notFound);
    }

    private Order requireActiveDelivery(Long driverId) {
        return findActiveDelivery(driverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You have no active delivery"));
    }

    /**
     * Orders a shop never answered would hold their stock forever. This ends
     * them as EXPIRED and returns the stock. Safe to run on several instances
     * at once: move() lets only one of them win each order.
     */
    @Scheduled(fixedDelayString = "${app.orders.expiry-check-ms}")
    @Transactional
    public void expireOverdue() {
        expirePlacedBefore(Instant.now().minus(acceptTimeout));
    }

    @Transactional
    public int expirePlacedBefore(Instant cutoff) {
        int expired = 0;
        for (Order order : orders.findTop200ByStatusAndCreatedAtBeforeOrderById(Status.PLACED, cutoff)) {
            if (orders.move(order.getId(), Status.PLACED, Status.EXPIRED) == 1) {
                restoreStock(order);
                expired++;
            }
        }
        return expired;
    }

    /** Throws 409 when the order is not in the status the action needs (someone else got there first). */
    private void move(Long orderId, Status from, Status to) {
        if (orders.move(orderId, from, to) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not " + from + " any more");
        }
    }

    /**
     * Puts the order's units back on the shelf. Call only after winning a status
     * change out of an active status before pickup, so it can never run twice for
     * one order. Lines the shop marked unavailable are skipped: the shop said it
     * does not have them, and their stock was already set to zero.
     */
    private void restoreStock(Order order) {
        for (OrderItem line : items.findByOrderIdInOrderById(List.of(order.getId()))) {
            if (line.getShopProductId() != null && !line.isUnavailable()) {
                stock.restore(order.getShopId(), line.getShopProductId(), line.getQuantity());
            }
        }
    }

    // ---------------------------------------------------------------- admin

    /**
     * Admin cancels an order that is not finished. Before pickup the stock goes
     * back to the shop; after pickup the goods have left the shop, so it does not.
     * If the order moves on at the same moment (the shop accepts, the driver
     * picks up), admin gets 409 and can look again.
     */
    @Transactional
    public AdminOrderView adminCancel(Long orderId, String reason) {
        Order order = require(orderId);
        if (!ACTIVE.contains(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is already finished: " + order.getStatus());
        }
        if (orders.cancel(orderId, order.getStatus(), Status.CANCELLED, reason) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The order just changed. Look at it again and retry.");
        }
        if (order.getStatus() != Status.PICKED_UP) {
            restoreStock(order);
        }
        // ponytail: the customer's refund goes here when the payment part exists.
        return getForAdmin(orderId);
    }

    /**
     * Admin gives the job to a driver, taking it from any driver who had it.
     * The caller checks the driver is approved. The driver must be free; a
     * unique index in the database backs this up if two assignments race.
     */
    @Transactional
    public AdminOrderView adminAssignDriver(Long orderId, Long driverId) {
        Order order = require(orderId);
        if (!driverId.equals(order.getDriverId())) {
            if (orders.existsByDriverIdAndStatusIn(driverId, WITH_A_DRIVER)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "That driver is on another delivery");
            }
            if (orders.setDriver(orderId, driverId, WAITING_FOR_PICKUP) == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Only orders waiting for pickup (ACCEPTED or READY) can be assigned");
            }
        }
        return getForAdmin(orderId);
    }

    /** Takes the job off its driver before pickup; it goes back to the pool for any driver. */
    @Transactional
    public AdminOrderView adminRemoveDriver(Long orderId) {
        require(orderId);
        if (orders.clearDriver(orderId, WAITING_FOR_PICKUP) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The order has no driver, or is already picked up");
        }
        return getForAdmin(orderId);
    }

    /** Every order, newest first, narrowed by any of the filters given. needsDriver = waiting for pickup with no driver. */
    @Transactional(readOnly = true)
    public Page<AdminOrderView> listForAdmin(Status status, Long shopId, Long customerId, Long driverId,
            boolean needsDriver, int page, int size) {
        Specification<Order> filters = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (shopId != null) {
                where.add(cb.equal(root.get("shopId"), shopId));
            }
            if (customerId != null) {
                where.add(cb.equal(root.get("customerId"), customerId));
            }
            if (driverId != null) {
                where.add(cb.equal(root.get("driverId"), driverId));
            }
            if (needsDriver) {
                where.add(cb.isNull(root.get("driverId")));
                where.add(root.get("status").in(WAITING_FOR_PICKUP));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Page<Order> found = orders.findAll(filters, newestFirst(page, size));
        return adminViews(found);
    }

    @Transactional(readOnly = true)
    public AdminOrderView getForAdmin(Long orderId) {
        return adminViews(new PageImpl<>(List.of(require(orderId)))).getContent().get(0);
    }

    /** Builds admin views for a page of orders with one query each for lines, shop names and user names. */
    private Page<AdminOrderView> adminViews(Page<Order> found) {
        Map<Long, List<OrderItem>> lines = linesOf(found.getContent());
        Map<Long, String> shopNames = shops.names(found.stream().map(Order::getShopId).collect(Collectors.toSet()));
        Set<Long> userIds = new HashSet<>();
        found.forEach(o -> {
            userIds.add(o.getCustomerId());
            if (o.getDriverId() != null) {
                userIds.add(o.getDriverId());
            }
        });
        Map<Long, String> userNames = users.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, User::getName));
        return found.map(o -> OrderViews.admin(o, shopNames.get(o.getShopId()), userNames,
                lines.getOrDefault(o.getId(), List.of())));
    }

    // -------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public CustomerOrderView getForCustomer(Long customerId, Long orderId) {
        return customerView(forCustomer(customerId, orderId));
    }

    @Transactional(readOnly = true)
    public Page<CustomerOrderView> listForCustomer(Long customerId, int page, int size) {
        Page<Order> found = orders.findByCustomerId(customerId, newestFirst(page, size));
        Map<Long, List<OrderItem>> lines = linesOf(found.getContent());
        Map<Long, String> shopNames = shops.names(found.stream().map(Order::getShopId).collect(Collectors.toSet()));
        return found.map(o -> OrderViews.customer(o, shopNames.get(o.getShopId()), lines.getOrDefault(o.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public ShopOrderView getForShop(Long shopId, Long orderId) {
        return shopView(forShop(shopId, orderId));
    }

    /** status null = all of the shop's orders. */
    @Transactional(readOnly = true)
    public Page<ShopOrderView> listForShop(Long shopId, Status status, int page, int size) {
        Pageable pageable = newestFirst(page, size);
        Page<Order> found = status == null
                ? orders.findByShopId(shopId, pageable)
                : orders.findByShopIdAndStatus(shopId, status, pageable);
        Map<Long, List<OrderItem>> lines = linesOf(found.getContent());
        return found.map(o -> OrderViews.shop(o, lines.getOrDefault(o.getId(), List.of())));
    }

    private static Pageable newestFirst(int page, int size) {
        return Paging.of(page, size).withSort(Sort.by(Sort.Direction.DESC, "id"));
    }

    /** An order of another customer answers "not found", the same as one that does not exist. */
    private Order forCustomer(Long customerId, Long orderId) {
        return orders.findByIdAndCustomerId(orderId, customerId).orElseThrow(OrderService::notFound);
    }

    private Order forShop(Long shopId, Long orderId) {
        return orders.findByIdAndShopId(orderId, shopId).orElseThrow(OrderService::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
    }

    private CustomerOrderView customerView(Order order) {
        String shopName = shops.names(List.of(order.getShopId())).get(order.getShopId());
        return OrderViews.customer(order, shopName, linesOf(List.of(order)).getOrDefault(order.getId(), List.of()));
    }

    private ShopOrderView shopView(Order order) {
        return OrderViews.shop(order, linesOf(List.of(order)).getOrDefault(order.getId(), List.of()));
    }

    /** All lines of the given orders in one query, grouped by order id. */
    private Map<Long, List<OrderItem>> linesOf(List<Order> list) {
        return items.findByOrderIdInOrderById(list.stream().map(Order::getId).toList()).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
    }
}
