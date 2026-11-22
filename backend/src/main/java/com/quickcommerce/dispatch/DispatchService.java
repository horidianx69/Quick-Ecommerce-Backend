package com.quickcommerce.dispatch;

import com.quickcommerce.auth.User;
import com.quickcommerce.auth.UserRepository;
import com.quickcommerce.common.Geo;
import com.quickcommerce.common.Settings;
import com.quickcommerce.order.Order;
import com.quickcommerce.order.Order.Status;
import com.quickcommerce.order.OrderService;
import com.quickcommerce.shop.Shop;
import com.quickcommerce.shop.ShopService;
import com.quickcommerce.work.CodeTriesUsed;
import com.quickcommerce.work.DriverWork;
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

    /**
     * A job in the list, before the driver commits. Distances only: nothing here says who or where exactly.
     * payCents is an estimate: engaged time at the hourly rate plus the per-km allowance, for these distances.
     */
    public record JobView(long orderId, Status status, int units, int payCents, double pickupKm, double dropKm,
            String dropoffCity, Instant createdAt) {}

    /** One place to go. latitude/longitude feed the phone's maps app for turn-by-turn directions. */
    public record Stop(String name, String phone, String addressLine, String city, String province, String postalCode,
            double latitude, double longitude) {}

    /**
     * The driver's current delivery. dropoff and note stay null until the order
     * is picked up; until then dropoffCity and dropKm show roughly how far it goes.
     */
    /**
     * payCents is the estimate while the delivery is on, and the actual pay once delivered.
     * tipCents stays null until the order is delivered: the driver learns the tip only afterwards.
     */
    public record DeliveryView(long orderId, Status status, int units, int payCents, Stop pickup, Stop dropoff,
            String dropoffCity, double dropKm, String note, Instant createdAt, Integer tipCents) {}

    /** payCents = engaged-time pay plus km allowance for this order; tipCents goes to the driver in full. */
    public record PastDelivery(long orderId, int units, int payCents, int tipCents, Instant deliveredAt) {}

    private final DriverRepository drivers;
    private final DispatchQueries queries;
    private final OrderService orders;
    private final ShopService shops;
    private final UserRepository users;
    private final double offerRadiusKm;
    private final Duration locationMaxAge;
    private final DriverWork work;
    private final Settings settings;
    private final double minutesPerKm;

    DispatchService(DriverRepository drivers, DispatchQueries queries, OrderService orders, ShopService shops,
            UserRepository users, DriverWork work, Settings settings,
            @Value("${app.dispatch.offer-radius-km}") double offerRadiusKm,
            @Value("${app.dispatch.location-max-age}") Duration locationMaxAge,
            @Value("${app.browse.minutes-per-km}") double minutesPerKm) {
        this.work = work;
        this.settings = settings;
        this.minutesPerKm = minutesPerKm;
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
        Settings.Values s = settings.current();
        return queries.openJobsNear(driver.getLatitude(), driver.getLongitude(), offerRadiusKm).stream()
                .map(job -> new JobView(job.orderId(), Status.valueOf(job.status()), job.units(),
                        estimatePay(s, job.pickupKm() + job.dropKm()),
                        round(job.pickupKm()), round(job.dropKm()), job.city(), job.createdAt()))
                .toList();
    }

    /**
     * Pay for a trip of this many straight-line km: road km (x1.3) at the browse
     * speed (minutes per km), at the hourly rate, plus the per-km allowance.
     * Real pay is worked out from the actual engaged time when the job ends.
     */
    private int estimatePay(Settings.Values s, double straightKm) {
        double roadKm = straightKm * 1.3;
        double minutes = roadKm * minutesPerKm;
        return (int) Math.round(minutes / 60 * s.driverHourlyCents() + roadKm * s.driverPerKmCents());
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
        orders.assignDriver(orderId, driverId, driver.getLatitude(), driver.getLongitude());
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

    @Transactional(noRollbackFor = CodeTriesUsed.class)
    public DeliveryView delivered(Long driverId, String code) {
        Driver driver = drivers.findById(driverId).orElseThrow();
        return view(orders.require(orders.deliver(driverId, code, driver.getLatitude(), driver.getLongitude())));
    }

    @Transactional(readOnly = true)
    public Page<PastDelivery> history(Long driverId, int page, int size) {
        return orders.deliveredBy(driverId, page, size).map(order -> new PastDelivery(order.getId(),
                orders.unitCount(order.getId()), work.payFor(order.getId(), driverId), order.getTipCents(),
                order.getDeliveredAt()));
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
        boolean delivered = order.getStatus() == Status.DELIVERED;
        int pay = delivered ? work.payFor(order.getId(), order.getDriverId())
                : estimatePay(settings.current(), currentToShopKm(order, shop) + dropKm);
        return new DeliveryView(order.getId(), order.getStatus(), orders.unitCount(order.getId()),
                pay, pickup, dropoff, order.getCity(), round(dropKm),
                carrying ? order.getNote() : null, order.getCreatedAt(), delivered ? order.getTipCents() : null);
    }

    /** Straight-line km from the driver's last position to the shop, until pickup; 0 after. */
    private double currentToShopKm(Order order, Shop shop) {
        if (order.getStatus() == Status.PICKED_UP || order.getDriverId() == null) {
            return 0;
        }
        return drivers.findById(order.getDriverId())
                .filter(d -> d.getLatitude() != null)
                .map(d -> Geo.distanceKm(d.getLatitude(), d.getLongitude(), shop.getLatitude(), shop.getLongitude()))
                .orElse(0.0);
    }

    private static double round(double km) {
        return Math.round(km * 10) / 10.0;
    }
}
