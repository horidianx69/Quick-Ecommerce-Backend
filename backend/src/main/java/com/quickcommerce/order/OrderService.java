package com.quickcommerce.order;

import com.quickcommerce.auth.User;
import com.quickcommerce.auth.UserRepository;
import com.quickcommerce.catalog.ShopProductView;
import com.quickcommerce.catalog.StockService;
import com.quickcommerce.common.DatabaseTime;
import com.quickcommerce.common.Geo;
import com.quickcommerce.common.Paging;
import com.quickcommerce.common.Settings;
import com.quickcommerce.order.Order.Status;
import com.quickcommerce.order.OrderViews.AdminOrderView;
import com.quickcommerce.order.OrderViews.CustomerOrderView;
import com.quickcommerce.order.OrderViews.ShopOrderView;
import com.quickcommerce.payment.OrderPayments;
import com.quickcommerce.shop.Shop;
import com.quickcommerce.shop.ShopService;
import com.quickcommerce.work.DriverWork;
import jakarta.persistence.criteria.Predicate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every order rule lives here: placing, pricing, and each status change.
 * Controllers only translate HTTP; nothing else may change an order.
 */
@Service
public class OrderService {

    static final int MAX_UNANSWERED_ORDERS_PER_CUSTOMER = 3;
    private static final long MAX_ORDER_TOTAL_CENTS = Integer.MAX_VALUE;
    private static final int EXPIRY_BATCH_SIZE = 200;

    public record Placed(CustomerOrderView order, boolean created) {}

    /** The fees a new order at this shop would pay right now. */
    record Fees(int delivery, int handling, int smallCart, int lateNight, int surge) {
        int total() {
            return delivery + handling + smallCart + lateNight + surge;
        }
    }

    public record QuoteLine(Long shopProductId, Long productId, String name, int quantity, int unitPriceCents,
            boolean inStock) {}

    /**
     * What an order would cost if placed now, fee by fee, without placing it.
     * The thresholds let the app say "add $X for free delivery" or "add $X to skip the small-cart fee".
     */
    public record Quote(Long shopId, List<QuoteLine> items, long subtotalCents, int deliveryFeeCents, int handlingFeeCents,
            int smallCartFeeCents, int lateNightFeeCents, int surgeFeeCents, int tipCents, long totalCents,
            int smallCartBelowCents, int deliveryMidFromCents, int freeDeliveryFromCents, List<Integer> tipOptionsCents) {}

    /** Orders that can no longer take a tip. */
    static final List<Status> CLOSED = List.of(Status.REJECTED, Status.CANCELLED, Status.EXPIRED);
    /** How far (km) a driver may mark an order delivered from the address before the customer is asked about it. */
    static final double SUSPICIOUS_DELIVERY_KM = 0.2;
    static final Duration AFTER_DELIVERY_WINDOW = Duration.ofHours(24);

    /** A driver can take an order from the moment the shop accepts it, and travel while it is packed. */
    static final List<Status> WAITING_FOR_PICKUP = List.of(Status.ACCEPTED, Status.READY);
    static final List<Status> WITH_A_DRIVER = List.of(Status.ACCEPTED, Status.READY, Status.PICKED_UP);
    /** Not finished yet: admin can still cancel these. */
    static final List<Status> ACTIVE = List.of(Status.PAYMENT_PENDING, Status.PLACED, Status.CAPTURE_PENDING,
            Status.ACCEPTED, Status.READY, Status.PICKED_UP);
    static final int DELIVERY_CODE_TRIES = 5;
    private static final Duration DELIVERY_CODE_WINDOW = Duration.ofMinutes(15);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final StockService stock;
    private final ShopService shops;
    private final Pricing pricing;
    private final double maxDistanceKm;
    private final Duration acceptTimeout;
    private final UserRepository users;
    private final DriverWork work;
    private final SurgeCheck surge;
    private final JsonMapper json;
    private final DatabaseTime time;
    private final TransactionTemplate expiryTransaction;
    private final OrderPayments payments;

    OrderService(OrderRepository orders, OrderItemRepository items, StockService stock, ShopService shops,
            Pricing pricing, UserRepository users, DriverWork work, SurgeCheck surge, JsonMapper json,
            DatabaseTime time, OrderPayments payments, PlatformTransactionManager transactionManager,
            @Value("${app.orders.max-distance-km}") double maxDistanceKm,
            @Value("${app.orders.accept-timeout}") Duration acceptTimeout) {
        this.orders = orders;
        this.items = items;
        this.stock = stock;
        this.shops = shops;
        this.pricing = pricing;
        this.users = users;
        this.work = work;
        this.surge = surge;
        this.maxDistanceKm = maxDistanceKm;
        this.acceptTimeout = acceptTimeout;
        this.json = json;
        this.time = time;
        this.payments = payments;
        this.expiryTransaction = new TransactionTemplate(transactionManager);
        this.expiryTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
            DeliveryAddress address, String note, int tipCents) {
        // One customer cannot create orders in parallel past the unanswered-order limit.
        User customer = users.findWithLockById(customerId)
                .filter(u -> !u.isDisabled())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        String fingerprint = fingerprint(shopId, quantities, address, cleanNote, tipCents);
        var replay = orders.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
        if (replay.isPresent()) {
            Order existing = replay.get();
            if (existing.getCheckoutFingerprint() != null && !existing.getCheckoutFingerprint().equals(fingerprint)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This checkout key was used for a different order");
            }
            return new Placed(customerView(existing), false);
        }
        if (orders.countByCustomerIdAndStatusIn(customerId, List.of(Status.PAYMENT_PENDING, Status.PLACED, Status.CAPTURE_PENDING)) >= MAX_UNANSWERED_ORDERS_PER_CUSTOMER) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You have orders waiting for a shop to answer. Try again once they are answered.");
        }
        Shop shop = requireOrderable(shopId, address);
        Pricing.Prices prices = pricing.current();
        requireTipOption(tipCents, prices);

        List<ShopProductView> reserved = stock.reserve(shopId, quantities);
        long subtotal = reserved.stream()
                .mapToLong(p -> (long) quantities.get(p.id()) * prices.customerPrice(p.priceCents(), p.category())).sum();
        Fees fees = fees(prices, shop, subtotal);
        requireAllowedTotal(subtotal + fees.total() + tipCents);
        payments.validateAmount(subtotal + fees.total() + tipCents);

        Order order = new Order();
        order.setCustomerId(customer.getId());
        order.setShopId(shopId);
        order.setIdempotencyKey(idempotencyKey);
        order.setCheckoutFingerprint(fingerprint);
        order.setStatus(payments.enabled() ? Status.PAYMENT_PENDING : Status.PLACED);
        order.setAddressLine(address.addressLine().trim());
        order.setCity(address.city().trim());
        order.setProvince(address.province());
        order.setPostalCode(address.postalCode().toUpperCase(Locale.ROOT));
        order.setLatitude(address.latitude());
        order.setLongitude(address.longitude());
        order.setNote(cleanNote);
        order.setDeliveryFeeCents(fees.delivery());
        order.setHandlingFeeCents(fees.handling());
        order.setSmallCartFeeCents(fees.smallCart());
        order.setLateNightFeeCents(fees.lateNight());
        order.setSurgeFeeCents(fees.surge());
        order.setTipCents(tipCents);
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
            line.setShopPriceCents(prices.payout(product.priceCents()));
            line.setCustomerPriceCents(prices.customerPrice(product.priceCents(), product.category()));
            lines.add(line);
        }
        items.saveAll(lines);
        if (payments.enabled()) {
            payments.open(order.getId(), subtotal + fees.total() + tipCents);
        }
        return new Placed(customerView(order), true);
    }

    /** The order's cost if placed now, without placing it or taking stock. Same checks as placing, except stock. */
    @Transactional(readOnly = true)
    public Quote quote(Long shopId, Map<Long, Integer> quantities, DeliveryAddress address, int tipCents) {
        Shop shop = requireOrderable(shopId, address);
        Pricing.Prices prices = pricing.current();
        requireTipOption(tipCents, prices);
        List<QuoteLine> lines = new ArrayList<>();
        long subtotal = 0;
        for (ShopProductView p : stock.peek(shopId, quantities.keySet())) {
            int quantity = quantities.get(p.id());
            int unit = prices.customerPrice(p.priceCents(), p.category());
            lines.add(new QuoteLine(p.id(), p.productId(), p.name(), quantity, unit, p.quantity() >= quantity));
            subtotal += (long) unit * quantity;
        }
        Fees fees = fees(prices, shop, subtotal);
        long total = subtotal + fees.total() + tipCents;
        requireAllowedTotal(total);
        payments.validateAmount(total);
        Settings.Values s = prices.settings();
        return new Quote(shopId, lines, subtotal, fees.delivery(), fees.handling(), fees.smallCart(), fees.lateNight(),
                fees.surge(), tipCents, total,
                s.smallCartBelowCents(), s.deliveryMidFromCents(), s.freeDeliveryFromCents(), s.tipOptionsCents());
    }

    /** Approved, open right now, and within delivery range of the address. */
    private Shop requireOrderable(Long shopId, DeliveryAddress address) {
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
        return shop;
    }

    private Fees fees(Pricing.Prices prices, Shop shop, long subtotalCents) {
        Settings.Values s = prices.settings();
        boolean lateNight = s.isLateNight(time.localNow(shop.getTimeZone()).toLocalTime());
        return new Fees(s.deliveryFee(subtotalCents), s.handlingFeeCents(), s.smallCartFee(subtotalCents),
                lateNight ? s.lateNightFeeCents() : 0, surge.isActive(shop, s) ? s.surgeFeeCents() : 0);
    }

    private static void requireAllowedTotal(long total) {
        if (total > MAX_ORDER_TOTAL_CENTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order total exceeds the maximum allowed amount");
        }
    }

    private String fingerprint(Long shopId, Map<Long, Integer> quantities, DeliveryAddress address, String note, int tipCents) {
        byte[] request = json.writeValueAsBytes(List.of(shopId, new TreeMap<>(quantities), address.addressLine().trim(),
                address.city().trim(), address.province(), address.postalCode().replace(" ", "").toUpperCase(Locale.ROOT),
                address.latitude(), address.longitude(), note == null ? "" : note, tipCents));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void requireTipOption(int tipCents, Pricing.Prices prices) {
        if (tipCents != 0 && !prices.settings().tipOptionsCents().contains(tipCents)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tipCents must be 0 or one of " + prices.settings().tipOptionsCents());
        }
    }

    // ------------------------------------------------- tips and receipt

    /**
     * Adds a tip after checkout: once, while the order is not closed, up to 24 h
     * after delivery. Goes to the driver in full.
     */
    @Transactional
    public CustomerOrderView tip(Long customerId, Long orderId, int tipCents) {
        Order order = forCustomer(customerId, orderId);
        if (payments.exists(orderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Start a Stripe checkout to add a tip after delivery");
        }
        if (tipCents == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tipCents must not be 0");
        }
        requireTipOption(tipCents, pricing.current());
        if (order.getTipCents() == 0) {
            long subtotal = OrderViews.subtotal(items.findByOrderIdInOrderById(List.of(orderId)));
            requireAllowedTotal(subtotal + order.feesCents() + tipCents);
        }
        if (orders.tip(orderId, customerId, tipCents, CLOSED, Instant.now().minus(AFTER_DELIVERY_WINDOW)) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This order already has a tip, was not delivered, or was delivered over 24 hours ago");
        }
        return customerView(forCustomer(customerId, orderId));
    }

    @Transactional
    public OrderPayments.CustomerPayment checkoutTip(long customerId, long orderId, int cents) {
        if (!payments.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stripe payments are disabled");
        }
        Order order = orders.findWithLockById(orderId).filter(o -> o.getCustomerId() == customerId)
                .orElseThrow(OrderService::notFound);
        if (order.getStatus() != Status.DELIVERED || !payments.orderCaptured(orderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A delivered order with a captured payment is required");
        }
        var replay = payments.existingTip(orderId, customerId, cents);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (order.getTipCents() != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An order can be tipped only once");
        }
        requireTipOption(cents, pricing.current());
        return payments.openTip(orderId, customerId, cents, order.getDeliveredAt().plus(AFTER_DELIVERY_WINDOW));
    }

    /**
     * The customer's answer to "Did you receive your order?". Accepted when the
     * app asked, or as a report within 24 h of delivery. "No" records a violation
     * for the driver and alerts admin.
     */
    @Transactional
    public CustomerOrderView answerReceipt(Long customerId, Long orderId, boolean received) {
        Order order = forCustomer(customerId, orderId);
        if (orders.answerReceipt(orderId, customerId, received ? "RECEIVED" : "NOT_RECEIVED", Status.DELIVERED,
                Instant.now().minus(AFTER_DELIVERY_WINDOW)) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Already answered, not delivered, or delivered over 24 hours ago");
        }
        if (!received && order.getDriverId() != null) {
            work.notReceived(order.getDriverId(), orderId);
        }
        return customerView(forCustomer(customerId, orderId));
    }

    // Payment worker calls these with the order row locked in its finalization transaction.
    public void confirmAuthorization(long orderId, Instant placedAt) {
        orders.authorizePayment(orderId, Status.PAYMENT_PENDING, Status.PLACED, placedAt);
    }

    public void confirmCapture(long orderId) {
        move(orderId, Status.CAPTURE_PENDING, Status.ACCEPTED);
    }

    public void expirePaymentOrder(Order order, Status from) {
        if (orders.move(order.getId(), from, Status.EXPIRED) == 1) {
            restoreStock(order);
            payments.close(order.getId());
        }
    }

    // ----------------------------------------------------- status changes

    /** Customer changes their mind. Only possible while the shop has not answered. */
    @Transactional
    public CustomerOrderView cancel(Long customerId, Long orderId) {
        Order order = forCustomer(customerId, orderId);
        if ((order.getStatus() != Status.PAYMENT_PENDING && order.getStatus() != Status.PLACED)
                || orders.cancel(orderId, order.getStatus(), Status.CANCELLED, null) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The order is no longer waiting for payment or shop acceptance");
        }
        restoreStock(order);
        payments.close(orderId);
        return customerView(forCustomer(customerId, orderId));
    }

    /**
     * Shop takes the order. Items it turns out not to have are marked
     * unavailable (not charged, not paid out) and that product's stock is set
     * to zero, because the count on file was wrong.
     */
    @Transactional
    public ShopOrderView accept(Long shopId, Long orderId, Set<Long> unavailableItemIds) {
        Order order = forShop(shopId, orderId);
        List<OrderItem> lines = items.findByOrderIdInOrderById(List.of(orderId));
        Set<Long> lineIds = lines.stream().map(OrderItem::getId).collect(Collectors.toSet());
        if (!lineIds.containsAll(unavailableItemIds)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unavailableItemIds has an item that is not in this order");
        }
        if (unavailableItemIds.size() == lines.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every item is unavailable: reject the order instead");
        }
        boolean paidOrder = payments.exists(orderId);
        move(orderId, Status.PLACED, paidOrder ? Status.CAPTURE_PENDING : Status.ACCEPTED);
        if (paidOrder) {
            long subtotal = lines.stream().filter(line -> !unavailableItemIds.contains(line.getId()))
                    .mapToLong(line -> (long) line.getQuantity() * line.getCustomerPriceCents()).sum();
            payments.capture(orderId, subtotal + order.feesCents() + order.getTipCents());
        }
        if (!unavailableItemIds.isEmpty()) {
            items.markUnavailable(orderId, unavailableItemIds);
            lines.stream()
                    .filter(line -> unavailableItemIds.contains(line.getId()) && line.getShopProductId() != null)
                    .sorted(Comparator.comparing(OrderItem::getShopProductId))
                    .forEach(line -> stock.markOutOfStock(shopId, line.getShopProductId()));
        }
        return shopView(forShop(shopId, orderId));
    }

    /** Shop says no. The stock it held goes back on the shelf. */
    @Transactional
    public ShopOrderView reject(Long shopId, Long orderId) {
        Order order = forShop(shopId, orderId);
        move(orderId, Status.PLACED, Status.REJECTED);
        restoreStock(order);
        payments.close(orderId);
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
    public void assignDriver(Long orderId, Long driverId, Double driverLat, Double driverLng) {
        if (orders.existsByDriverIdAndStatusIn(driverId, WITH_A_DRIVER)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Finish your current delivery first");
        }
        if (orders.assignDriver(orderId, driverId, WAITING_FOR_PICKUP) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This job has been taken");
        }
        startEngagement(require(orderId), driverId, driverLat, driverLng);
    }

    /** Engaged time starts now. Distances are straight lines from where the driver is, to the shop, to the door. */
    private void startEngagement(Order order, Long driverId, Double driverLat, Double driverLng) {
        Shop shop = shops.require(order.getShopId());
        double toShop = driverLat == null || driverLng == null ? 0
                : Geo.distanceKm(driverLat, driverLng, shop.getLatitude(), shop.getLongitude());
        double toCustomer = Geo.distanceKm(shop.getLatitude(), shop.getLongitude(), order.getLatitude(), order.getLongitude());
        work.start(order.getId(), driverId, toShop, toCustomer);
    }

    /** The driver gives the job back before pickup; it returns to the pool for other drivers. */
    @Transactional
    public void releaseDriver(Long driverId) {
        Order order = requireActiveDelivery(driverId);
        if (orders.releaseDriver(order.getId(), driverId, WAITING_FOR_PICKUP) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Too late: the order is already picked up");
        }
        work.end(order.getId(), DriverWork.EndReason.RELEASED);
    }

    @Transactional
    public void pickUp(Long driverId) {
        Order order = requireActiveDelivery(driverId);
        if (orders.moveForDriver(order.getId(), driverId, Status.READY, Status.PICKED_UP) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, order.getStatus() == Status.ACCEPTED
                    ? "The shop has not marked this order ready yet"
                    : "This order is already picked up");
        }
        work.pickedUp(order.getId());
    }

    /**
     * Ends the delivery. The driver must give the code only the customer has,
     * so an order cannot be marked delivered from somewhere else. Returns the order id.
     */
    @Transactional(noRollbackFor = DeliveryCodeRejection.class)
    public Long deliver(Long driverId, String code, Double driverLat, Double driverLng) {
        Order order = orders.findFirstWithLockByDriverIdAndStatusIn(driverId, WITH_A_DRIVER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You have no active delivery"));
        if (order.getStatus() != Status.PICKED_UP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pick the order up first");
        }
        // The order lock serializes attempts, and the database clock is shared by all replicas.
        Instant now = time.instant();
        Instant expiresAt = order.getDeliveryCodeWindowExpiresAt();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            order.setDeliveryCodeAttempts(0);
            order.setDeliveryCodeWindowExpiresAt(now.plus(DELIVERY_CODE_WINDOW));
        }
        if (order.getDeliveryCodeAttempts() >= DELIVERY_CODE_TRIES) {
            work.codeTriesUsed(order.getId());
            throw DeliveryCodeRejection.triesUsed();
        }
        order.setDeliveryCodeAttempts(order.getDeliveryCodeAttempts() + 1);
        boolean matches = MessageDigest.isEqual(
                order.getDeliveryCode().getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw DeliveryCodeRejection.wrongCode();
        }
        if (orders.deliver(order.getId(), driverId, now, Status.PICKED_UP, Status.DELIVERED) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is already delivered");
        }
        boolean locationLost = work.end(order.getId(), DriverWork.EndReason.DELIVERED)
                .map(DriverWork.Engagement::locationLost).orElse(false);
        // Marked delivered far from the door, or with no location during the trip: ask the customer.
        boolean farFromDoor = driverLat == null || driverLng == null
                || Geo.distanceKm(driverLat, driverLng, order.getLatitude(), order.getLongitude()) > SUSPICIOUS_DELIVERY_KM;
        if (locationLost || farFromDoor) {
            orders.askReceipt(order.getId());
        }
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
    public void expireOverdue() {
        expirePlacedBefore(Instant.now().minus(acceptTimeout));
        for (Long orderId : payments.expiredUnopened()) {
            expiryTransaction.executeWithoutResult(tx ->
                    orders.findWithLockById(orderId).ifPresent(order -> expirePaymentOrder(order, Status.PAYMENT_PENDING)));
        }
    }

    public int expirePlacedBefore(Instant cutoff) {
        int expired = 0;
        ExpiryBatch batch;
        do {
            batch = expiryTransaction.execute(status -> expireBatch(cutoff));
            expired += batch.expired();
        } while (batch.found() == EXPIRY_BATCH_SIZE);
        return expired;
    }

    private record ExpiryBatch(int found, int expired) {}

    private ExpiryBatch expireBatch(Instant cutoff) {
        List<Order> batch = orders.findTop200ByStatusAndCreatedAtBeforeOrderById(Status.PLACED, cutoff);
        Map<Long, Long> expired = new HashMap<>();
        for (Order order : batch) {
            if (orders.move(order.getId(), Status.PLACED, Status.EXPIRED) == 1) {
                expired.put(order.getId(), order.getShopId());
            }
        }
        restoreStock(expired);
        expired.keySet().stream().sorted().forEach(payments::close);
        return new ExpiryBatch(batch.size(), expired.size());
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
        restoreStock(Map.of(order.getId(), order.getShopId()));
    }

    private void restoreStock(Map<Long, Long> shopByOrderId) {
        if (!shopByOrderId.isEmpty()) {
            items.findByOrderIdInOrderById(shopByOrderId.keySet()).stream()
                    .filter(line -> line.getShopProductId() != null && !line.isUnavailable())
                    .sorted(Comparator.comparing(OrderItem::getShopProductId))
                    .forEach(line -> stock.restore(shopByOrderId.get(line.getOrderId()), line.getShopProductId(), line.getQuantity()));
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
        work.end(orderId, DriverWork.EndReason.CANCELLED); // the driver is paid for the time held
        if (order.getStatus() != Status.PICKED_UP) {
            restoreStock(order);
        }
        payments.close(orderId);
        return getForAdmin(orderId);
    }

    /**
     * Admin gives the job to a driver, taking it from any driver who had it.
     * The caller checks the driver is approved. The driver must be free; a
     * unique index in the database backs this up if two assignments race.
     */
    @Transactional
    public AdminOrderView adminAssignDriver(Long orderId, Long driverId, Double driverLat, Double driverLng) {
        Order order = require(orderId);
        if (!driverId.equals(order.getDriverId())) {
            if (orders.existsByDriverIdAndStatusIn(driverId, WITH_A_DRIVER)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "That driver is on another delivery");
            }
            if (orders.setDriver(orderId, driverId, WAITING_FOR_PICKUP) == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Only orders waiting for pickup (ACCEPTED or READY) can be assigned");
            }
            work.end(orderId, DriverWork.EndReason.UNASSIGNED); // the previous driver, if any
            startEngagement(order, driverId, driverLat, driverLng);
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
        work.end(orderId, DriverWork.EndReason.UNASSIGNED);
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
                lines.getOrDefault(o.getId(), List.of()), work.payFor(o.getId())));
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
        Map<Long, String> riders = driverNames(found.getContent());
        return found.map(o -> OrderViews.customer(o, shopNames.get(o.getShopId()), lines.getOrDefault(o.getId(), List.of()),
                riders.get(o.getDriverId())));
    }

    @Transactional(readOnly = true)
    public ShopOrderView getForShop(Long shopId, Long orderId) {
        return shopView(forShop(shopId, orderId));
    }

    /** status null = all of the shop's orders. */
    @Transactional(readOnly = true)
    public Page<ShopOrderView> listForShop(Long shopId, Status status, int page, int size) {
        Pageable pageable = newestFirst(page, size);
        if (status == Status.PAYMENT_PENDING) {
            return Page.empty(pageable);
        }
        Page<Order> found = status == null
                ? orders.findByShopIdAndStatusNot(shopId, Status.PAYMENT_PENDING, pageable)
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
        return orders.findByIdAndShopId(orderId, shopId).filter(o -> o.getStatus() != Status.PAYMENT_PENDING)
                .orElseThrow(OrderService::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
    }

    private CustomerOrderView customerView(Order order) {
        String shopName = shops.names(List.of(order.getShopId())).get(order.getShopId());
        return OrderViews.customer(order, shopName, linesOf(List.of(order)).getOrDefault(order.getId(), List.of()),
                driverNames(List.of(order)).get(order.getDriverId()));
    }

    private ShopOrderView shopView(Order order) {
        return OrderViews.shop(order, linesOf(List.of(order)).getOrDefault(order.getId(), List.of()));
    }

    /** Names of the riders on these orders, in one query. A HashMap, so orders with no rider (null id) just get null. */
    private Map<Long, String> driverNames(List<Order> list) {
        Set<Long> ids = list.stream().map(Order::getDriverId).filter(id -> id != null).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) users.findAllById(ids).forEach(u -> names.put(u.getId(), u.getName()));
        return names;
    }

    /** All lines of the given orders in one query, grouped by order id. */
    private Map<Long, List<OrderItem>> linesOf(List<Order> list) {
        return items.findByOrderIdInOrderById(list.stream().map(Order::getId).toList()).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
    }
}
