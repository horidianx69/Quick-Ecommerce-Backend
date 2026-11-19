package com.quickcommerce.dispatch;

import com.quickcommerce.auth.User;
import com.quickcommerce.auth.UserRepository;
import com.quickcommerce.common.Geo;
import com.quickcommerce.order.Order;
import com.quickcommerce.order.Order.Status;
import com.quickcommerce.order.OrderService;
import com.quickcommerce.shop.Shop;
import com.quickcommerce.shop.ShopService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Decides WHO may take a job and WHAT a driver may see at each step. When a
 * step is allowed is decided by OrderService, with the other order rules.
 *
 * What the driver sees, step by step:
 *  - job list:    distances, unit count and pay. No names, no addresses.
 *  - accepted:    the shop's name, phone, address and real location.
 *  - picked up:   the customer's name, address, location and note.
 *  - at no step:  which products are in the bag. Only how many units.
 */
@Service
public class DispatchService {

    /** A job in the list, before the driver commits. Distances only: nothing here says who or where exactly. */
    public record JobView(long orderId, Status status, int units, int payCents, double pickupKm, double dropKm,
            String dropoffCity, Instant createdAt) {}

    /** One place to go. latitude/longitude feed the phone's maps app for turn-by-turn directions. */
    public record Stop(String name, String phone, String addressLine, String city, String province, String postalCode,
            double latitude, double longitude) {}

    /**
     * The driver's current delivery. dropoff and note stay null until the order
     * is picked up; until then dropoffCity and dropKm show roughly how far it goes.
     */
    public record DeliveryView(long orderId, Status status, int units, int payCents, Stop pickup, Stop dropoff,
            String dropoffCity, double dropKm, String note, Instant createdAt) {}

    public record PastDelivery(long orderId, int units, int payCents, Instant deliveredAt) {}

    private final DriverRepository drivers;
    private final DispatchQueries queries;
    private final OrderService orders;
    private final ShopService shops;
    private final UserRepository users;
    private final double offerRadiusKm;
    private final Duration locationMaxAge;

    DispatchService(DriverRepository drivers, DispatchQueries queries, OrderService orders, ShopService shops,
            UserRepository users,
            @Value("${app.dispatch.offer-radius-km}") double offerRadiusKm,
            @Value("${app.dispatch.location-max-age}") Duration locationMaxAge) {
        this.drivers = drivers;
        this.queries = queries;
        this.orders = orders;
        this.shops = shops;
        this.users = users;
        this.offerRadiusKm = offerRadiusKm;
        this.locationMaxAge = locationMaxAge;
    }

    /** Open jobs near the driver, oldest first. Empty while the driver is already on a delivery. */
    @Transactional(readOnly = true)
    public List<JobView> jobs(Long driverId) {
        Driver driver = requireWorking(driverId);
        if (orders.findActiveDelivery(driverId).isPresent()) {
            return List.of();
        }
        return queries.openJobsNear(driver.getLatitude(), driver.getLongitude(), offerRadiusKm).stream()
                .map(job -> new JobView(job.orderId(), Status.valueOf(job.status()), job.units(), pay(job.deliveryFeeCents()),
                        round(job.pickupKm()), round(job.dropKm()), job.city(), job.createdAt()))
                .toList();
    }

    /**
     * Takes a job. The driver must be working (approved, online, recently
     * located) and near the shop: the same test that put the job in their
     * list, so guessing an order id from another city gets "not found".
     */
    @Transactional
    public DeliveryView accept(Long driverId, Long orderId) {
        Driver driver = requireWorking(driverId);
        Order job = orders.findOpenJob(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This job has been taken"));
        Shop shop = shops.require(job.getShopId());
        if (Geo.distanceKm(shop.getLatitude(), shop.getLongitude(), driver.getLatitude(), driver.getLongitude()) > offerRadiusKm) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
        }
        orders.assignDriver(orderId, driverId);
        return view(orders.require(orderId));
    }

    @Transactional(readOnly = true)
    public DeliveryView current(Long driverId) {
        return view(orders.findActiveDelivery(driverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You have no active delivery")));
    }

    @Transactional
    public void release(Long driverId) {
        orders.releaseDriver(driverId);
    }

    @Transactional
    public DeliveryView pickedUp(Long driverId) {
        orders.pickUp(driverId);
        return current(driverId);
    }

    @Transactional
    public DeliveryView delivered(Long driverId, String code) {
        return view(orders.require(orders.deliver(driverId, code)));
    }

    @Transactional(readOnly = true)
    public Page<PastDelivery> history(Long driverId, int page, int size) {
        return orders.deliveredBy(driverId, page, size).map(order -> new PastDelivery(order.getId(),
                orders.unitCount(order.getId()), pay(order.getDeliveryFeeCents()), order.getDeliveredAt()));
    }

    private Driver requireWorking(Long driverId) {
        Driver driver = drivers.findById(driverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Create your driver profile first"));
        if (driver.getStatus() != Driver.Status.APPROVED || !driver.isOnline()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Go online first");
        }
        if (driver.getLocatedAt() == null || driver.getLocatedAt().isBefore(Instant.now().minus(locationMaxAge))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Your location is out of date. Send it again.");
        }
        return driver;
    }

    private DeliveryView view(Order order) {
        Shop shop = shops.require(order.getShopId());
        Stop pickup = new Stop(shop.getName(), shop.getPhone(), shop.getAddressLine(), shop.getCity(),
                shop.getProvince(), shop.getPostalCode(), shop.getLatitude(), shop.getLongitude());
        // The customer's details appear only once the bag is in the driver's hands.
        boolean carrying = order.getStatus() == Status.PICKED_UP;
        Stop dropoff = !carrying ? null : new Stop(
                users.findById(order.getCustomerId()).map(User::getName).orElse("Customer"),
                null, // ponytail: customers have no phone number on file yet (email login). Fill in with phone login.
                order.getAddressLine(), order.getCity(), order.getProvince(), order.getPostalCode(),
                order.getLatitude(), order.getLongitude());
        double dropKm = Geo.distanceKm(shop.getLatitude(), shop.getLongitude(), order.getLatitude(), order.getLongitude());
        return new DeliveryView(order.getId(), order.getStatus(), orders.unitCount(order.getId()),
                pay(order.getDeliveryFeeCents()), pickup, dropoff, order.getCity(), round(dropKm),
                carrying ? order.getNote() : null, order.getCreatedAt());
    }

    // ponytail: the driver is paid the order's delivery fee, in full. A placeholder
    // until the real pay rule (base + distance, tips, platform share) is decided.
    private static int pay(int deliveryFeeCents) {
        return deliveryFeeCents;
    }

    private static double round(double km) {
        return Math.round(km * 10) / 10.0;
    }
}
