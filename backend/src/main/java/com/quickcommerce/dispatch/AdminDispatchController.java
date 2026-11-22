package com.quickcommerce.dispatch;

import com.quickcommerce.order.OrderService;
import com.quickcommerce.order.OrderViews.AdminOrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin assigning drivers by hand, for jobs no driver takes or a driver who cannot continue. */
@RestController
@RequestMapping("/api/admin/orders/{orderId}")
public class AdminDispatchController {

    public record AssignRequest(@NotNull Long driverId) {}

    private final OrderService orders;
    private final DriverRepository drivers;

    AdminDispatchController(OrderService orders, DriverRepository drivers) {
        this.orders = orders;
        this.drivers = drivers;
    }

    /**
     * Gives an order waiting for pickup to an approved driver, replacing any
     * driver it had. The driver need not be online: admin has usually called them.
     */
    @PostMapping("/assign")
    AdminOrderView assign(@PathVariable Long orderId, @Valid @RequestBody AssignRequest req) {
        Driver driver = drivers.findById(req.driverId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found"));
        if (driver.getStatus() != Driver.Status.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That driver is not approved");
        }
        return orders.adminAssignDriver(orderId, driver.getUserId());
    }

    /** Takes the order off its driver before pickup; it goes back to the job list for any driver. */
    @PostMapping("/unassign")
    AdminOrderView unassign(@PathVariable Long orderId) {
        return orders.adminRemoveDriver(orderId);
    }
}
