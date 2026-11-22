package com.quickcommerce.shop;

import com.quickcommerce.common.Canada;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shops/me")
public class ShopController {

    // ponytail: the client sends latitude/longitude (from its map pin); the backend
    // does not geocode the address. Add server-side geocoding if pins prove unreliable.
    public record ShopRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "^\\+?[0-9 ()-]{7,20}$", message = "must be a phone number") String phone,
            @NotBlank @Size(max = 200) String addressLine,
            @NotBlank @Size(max = 80) String city,
            @NotBlank @Pattern(regexp = Canada.PROVINCE,
                    message = "must be a Canadian province code") String province,
            @NotBlank @Pattern(regexp = Canada.POSTAL_CODE,
                    message = "must be a Canadian postal code") String postalCode,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}

    private final ShopRepository shops;
    private final ShopService shopService;

    ShopController(ShopRepository shops, ShopService shopService) {
        this.shops = shops;
        this.shopService = shopService;
    }

    @GetMapping
    Shop get(@AuthenticationPrincipal Jwt jwt) {
        return shopService.requireShop(jwt);
    }

    /**
     * Creates the caller's shop (status PENDING) or updates it. Status and
     * opening hours are never set from here: admin sets both.
     */
    @PutMapping
    @Transactional
    Shop upsert(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ShopRequest req) {
        Long ownerId = Long.valueOf(jwt.getSubject());
        // Row lock: two saves of the same shop (a double-clicked Save), or a save
        // and an admin hours change, run in turn instead of at once.
        Shop shop = shops.findWithLockByOwnerId(ownerId).orElseGet(() -> {
            Shop fresh = new Shop();
            fresh.setOwnerId(ownerId);
            fresh.setStatus(Shop.Status.PENDING);
            return fresh;
        });
        shop.setName(req.name().trim());
        shop.setPhone(req.phone().trim());
        shop.setAddressLine(req.addressLine().trim());
        shop.setCity(req.city().trim());
        shop.setProvince(req.province());
        shop.setTimeZone(Canada.timeZone(req.province()));
        shop.setPostalCode(req.postalCode().toUpperCase());
        shop.moveTo(req.latitude(), req.longitude());
        return shops.save(shop);
    }
}
