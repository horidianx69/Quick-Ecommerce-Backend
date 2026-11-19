package com.quickcommerce.order;

import com.quickcommerce.order.OrderViews.ShopOrderView;
import com.quickcommerce.shop.ShopService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The shop's side of orders. The shop id always comes from the token via ShopService. */
@RestController
@RequestMapping("/api/shops/me/orders")
public class ShopOrderController {

    /** Ids of order items the shop turned out not to have. Leave empty to accept everything. */
    public record AcceptRequest(@Size(max = 50) Set<Long> unavailableItemIds) {}

    private final OrderService orderService;
    private final ShopService shopService;

    ShopOrderController(OrderService orderService, ShopService shopService) {
        this.orderService = orderService;
        this.shopService = shopService;
    }

    /** The shop's orders, newest first. ?status=PLACED is the inbox of orders waiting for an answer. */
    @GetMapping
    Page<ShopOrderView> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Order.Status status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return orderService.listForShop(shopId(jwt), status, page, size);
    }

    @GetMapping("/{id}")
    ShopOrderView get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return orderService.getForShop(shopId(jwt), id);
    }

    @PostMapping("/{id}/accept")
    ShopOrderView accept(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody(required = false) AcceptRequest req) {
        Set<Long> unavailable = req == null || req.unavailableItemIds() == null ? Set.of() : req.unavailableItemIds();
        return orderService.accept(shopId(jwt), id, unavailable);
    }

    @PostMapping("/{id}/reject")
    ShopOrderView reject(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return orderService.reject(shopId(jwt), id);
    }

    @PostMapping("/{id}/ready")
    ShopOrderView ready(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return orderService.ready(shopId(jwt), id);
    }

    private Long shopId(Jwt jwt) {
        return shopService.requireShop(jwt).getId();
    }
}
