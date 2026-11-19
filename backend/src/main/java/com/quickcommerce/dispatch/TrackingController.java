package com.quickcommerce.dispatch;

import com.quickcommerce.common.Geo;
import com.quickcommerce.order.Order;
import com.quickcommerce.order.Order.Status;
import com.quickcommerce.order.OrderService;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Where is my order: the customer's live view of the driver. */
@RestController
public class TrackingController {

    /**
     * driver is null until the order is PICKED_UP. Before that the driver is
     * on the way to, or standing at, the shop, and showing them on a map would
     * give away where the shop is.
     */
    public record Tracking(Status status, boolean driverAssigned, DriverSpot driver) {}

    /** etaMinutes = travel time from the driver's last position to the customer's door. */
    public record DriverSpot(double latitude, double longitude, Instant locatedAt, int etaMinutes) {}

    private final OrderService orders;
    private final DriverRepository drivers;
    private final double minutesPerKm;

    TrackingController(OrderService orders, DriverRepository drivers,
            @Value("${app.browse.minutes-per-km}") double minutesPerKm) {
        this.orders = orders;
        this.drivers = drivers;
        this.minutesPerKm = minutesPerKm;
    }

    /** The app polls this every few seconds while an order is on its way. Only the order's own customer may ask. */
    // ponytail: polling, not a WebSocket push. One cheap read per poll. Add push
    // if polling every few seconds from many customers ever shows up in load.
    @GetMapping("/api/orders/{id}/tracking")
    Tracking tracking(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Order order = orders.requireForCustomer(Long.valueOf(jwt.getSubject()), id);
        DriverSpot spot = null;
        if (order.getStatus() == Status.PICKED_UP && order.getDriverId() != null) {
            spot = drivers.findById(order.getDriverId())
                    .filter(driver -> driver.getLatitude() != null)
                    .map(driver -> new DriverSpot(driver.getLatitude(), driver.getLongitude(), driver.getLocatedAt(),
                            (int) Math.ceil(minutesPerKm * Geo.distanceKm(driver.getLatitude(), driver.getLongitude(),
                                    order.getLatitude(), order.getLongitude()))))
                    .orElse(null);
        }
        return new Tracking(order.getStatus(), order.getDriverId() != null, spot);
    }
}
