package com.quickcommerce.shop;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** The door other parts use to reach the shop part. */
@Service
public class ShopService {

    private final ShopRepository shops;

    ShopService(ShopRepository shops) {
        this.shops = shops;
    }

    /**
     * The shop of the logged-in user. Every shop-scoped query must take its
     * shop id from here (the token), never from a request body or path.
     */
    public Shop requireShop(Jwt jwt) {
        return shops.findByOwnerId(Long.valueOf(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Create your shop first"));
    }

    /**
     * A shop customers may order from. Pending and suspended shops answer
     * "not found", the same as a shop that does not exist.
     */
    public Shop requireApproved(Long shopId) {
        return shops.findById(shopId)
                .filter(shop -> shop.getStatus() == Shop.Status.APPROVED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop not found"));
    }

    /** The shop as stored, real location included. For the driver assigned to collect from it. */
    public Shop require(Long shopId) {
        return shops.findById(shopId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop not found"));
    }

    /** Shop id -> name, for showing a customer which shop an order went to. */
    public Map<Long, String> names(Collection<Long> shopIds) {
        return shops.findAllById(shopIds).stream().collect(Collectors.toMap(Shop::getId, Shop::getName));
    }
}
