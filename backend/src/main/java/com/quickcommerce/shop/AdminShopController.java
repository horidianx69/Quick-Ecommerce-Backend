package com.quickcommerce.shop;

import com.quickcommerce.common.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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

    /** Approve (APPROVED), suspend (SUSPENDED) or send back to review (PENDING). */
    @PutMapping("/{id}/status")
    @Transactional
    Shop setStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest req) {
        if (shops.setStatus(id, req.status()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop not found");
        }
        return shops.findById(id).orElseThrow();
    }
}
