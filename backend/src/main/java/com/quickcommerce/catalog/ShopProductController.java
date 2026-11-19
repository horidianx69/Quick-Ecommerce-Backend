package com.quickcommerce.catalog;

import com.quickcommerce.common.Paging;
import com.quickcommerce.shop.ShopService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** A shop's own products. The shop id always comes from the token via ShopService. */
@RestController
@RequestMapping("/api/shops/me/products")
public class ShopProductController {

    private static final int MAX_PRICE_CENTS = 1_000_000; // $10,000
    private static final int MAX_QUANTITY = ShopProductRepository.MAX_QUANTITY;

    public record AddRequest(
            @NotNull Long productId,
            @NotNull @Min(1) @Max(MAX_PRICE_CENTS) Integer priceCents,
            @NotNull @Min(0) @Max(MAX_QUANTITY) Integer quantity) {}

    /** Price only. Stock is changed through /stock, never set here. */
    public record PriceRequest(@NotNull @Min(1) @Max(MAX_PRICE_CENTS) Integer priceCents) {}

    /** Positive = stock arrived. Negative = correction (damaged, miscounted). Never zero. */
    public record StockRequest(@NotNull @Min(-100_000) @Max(100_000) Integer change) {}

    private final ShopProductRepository shopProducts;
    private final CatalogProductRepository catalog;
    private final ShopService shopService;

    ShopProductController(ShopProductRepository shopProducts, CatalogProductRepository catalog,
            ShopService shopService) {
        this.shopProducts = shopProducts;
        this.catalog = catalog;
        this.shopService = shopService;
    }

    @GetMapping
    Page<ShopProductView> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return shopProducts.list(shopId(jwt), Paging.of(page, size));
    }

    /** Adds an approved catalog product to this shop. The same product twice is a 409. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    ShopProductView add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddRequest req) {
        Long shopId = shopId(jwt);
        catalog.findById(req.productId())
                .filter(p -> p.getStatus() == CatalogProduct.Status.APPROVED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Product is not in the approved catalog"));
        ShopProduct sp = new ShopProduct();
        sp.setShopId(shopId);
        sp.setProductId(req.productId());
        sp.setPriceCents(req.priceCents());
        sp.setQuantity(req.quantity());
        return view(shopId, shopProducts.save(sp).getId());
    }

    @PutMapping("/{id}")
    @Transactional
    ShopProductView setPrice(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody PriceRequest req) {
        Long shopId = shopId(jwt);
        if (shopProducts.setPrice(shopId, id, req.priceCents()) == 0) {
            throw notFound();
        }
        return view(shopId, id);
    }

    @PostMapping("/{id}/stock")
    @Transactional
    ShopProductView changeStock(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody StockRequest req) {
        if (req.change() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "change must not be zero");
        }
        Long shopId = shopId(jwt);
        if (shopProducts.changeStock(shopId, id, req.change()) == 0) {
            if (!shopProducts.existsByIdAndShopId(id, shopId)) {
                throw notFound();
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Stock must stay between 0 and " + MAX_QUANTITY);
        }
        return view(shopId, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        if (shopProducts.deleteByIdAndShopId(id, shopId(jwt)) == 0) {
            throw notFound();
        }
    }

    private Long shopId(Jwt jwt) {
        return shopService.requireShop(jwt).getId();
    }

    private ShopProductView view(Long shopId, Long id) {
        return shopProducts.view(shopId, id).orElseThrow(ShopProductController::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found in your shop");
    }
}
