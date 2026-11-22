package com.quickcommerce.order;

import com.quickcommerce.order.OrderViews.AdminOrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin's order board. Assigning drivers by hand is in dispatch.AdminDispatchController. */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    /** reason is shown to the customer and the shop. */
    public record CancelRequest(@NotBlank @Size(max = 300) String reason) {}

    private final OrderService orderService;

    AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * All orders, newest first. Every filter is optional and they combine.
     * ?needsDriver=true = accepted or ready, with no driver: the orders to watch.
     */
    @GetMapping
    Page<AdminOrderView> list(@RequestParam(required = false) Order.Status status,
            @RequestParam(required = false) Long shopId, @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long driverId, @RequestParam(defaultValue = "false") boolean needsDriver,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return orderService.listForAdmin(status, shopId, customerId, driverId, needsDriver, page, size);
    }

    @GetMapping("/{id}")
    AdminOrderView get(@PathVariable Long id) {
        return orderService.getForAdmin(id);
    }

    /** Cancels any unfinished order. Stock goes back to the shop unless the order was already picked up. */
    @PostMapping("/{id}/cancel")
    AdminOrderView cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest req) {
        return orderService.adminCancel(id, req.reason().trim());
    }
}
