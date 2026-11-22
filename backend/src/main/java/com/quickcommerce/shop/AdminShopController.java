package com.quickcommerce.shop;

import com.quickcommerce.common.Paging;
import jakarta.validation.Valid;
import com.quickcommerce.shop.Shop.Hours;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/shops")
public class AdminShopController {

    public record StatusRequest(@NotNull Shop.Status status) {}

    /** The whole week: one entry per open day; days left out are closed. */
    public record HoursRequest(@NotNull @Size(max = 7) List<@Valid @NotNull Hours> hours) {}

    private final ShopRepository shops;

    AdminShopController(ShopRepository shops) {
        this.shops = shops;
    }

    /** All shops, or only those with the given status (e.g. ?status=PENDING for the approval queue). */
    @GetMapping
    Page<Shop> list(@RequestParam(required = false) Shop.Status status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = Paging.of(page, size).withSort(Sort.by("id"));
        return status == null ? shops.findAll(pageable) : shops.findByStatus(status, pageable);
    }

    @GetMapping("/{id}")
    Shop get(@PathVariable Long id) {
        return shops.findById(id).orElseThrow(AdminShopController::notFound);
    }

    /** Replaces the shop's opening hours. Customers can order only while the shop is open. */
    @PutMapping("/{id}/hours")
    @Transactional
    Shop setHours(@PathVariable Long id, @Valid @RequestBody HoursRequest req) {
        Shop.validateHours(req.hours());
        Shop shop = shops.findWithLockById(id).orElseThrow(AdminShopController::notFound);
        shop.getHours().clear();
        shop.getHours().addAll(req.hours());
        return shop;
    }

    /** Approve (APPROVED), suspend (SUSPENDED) or send back to review (PENDING). */
    @PutMapping("/{id}/status")
    @Transactional
    Shop setStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest req) {
        if (shops.setStatus(id, req.status()) == 0) {
            throw notFound();
        }
        return shops.findById(id).orElseThrow();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop not found");
    }
}
