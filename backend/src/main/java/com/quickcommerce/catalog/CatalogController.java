package com.quickcommerce.catalog;

import com.quickcommerce.catalog.CatalogProduct.Status;
import com.quickcommerce.catalog.CatalogProduct.TaxCategory;
import com.quickcommerce.common.Paging;
import com.quickcommerce.shop.ShopService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class CatalogController {

    /** What a shop sends to ask for a missing product. Admin fills in the rest on review. */
    public record ProductRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 100) String brand,
            @Size(max = 32) String barcode,
            @NotBlank @Size(max = 80) String category) {}

    public record AdminProductRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 100) String brand,
            @Size(max = 32) String barcode,
            @NotBlank @Size(max = 80) String category,
            @NotNull TaxCategory taxCategory,
            // Only https links (or empty): a "javascript:" or plain-http link would be unsafe in the apps.
            @Size(max = 500) @Pattern(regexp = "^(|https://\\S+)$", message = "must be an https:// link") String imageUrl,
            @NotNull Status status) {}

    static final int MAX_OPEN_REQUESTS_PER_SHOP = 50;

    private final CatalogProductRepository products;
    private final ShopService shopService;

    CatalogController(CatalogProductRepository products, ShopService shopService) {
        this.products = products;
        this.shopService = shopService;
    }

    /** Approved products only. Any logged-in user. */
    @GetMapping("/api/catalog")
    Page<CatalogProduct> search(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return products.search(Status.APPROVED, q, Paging.of(page, size));
    }

    /** Every category that has an approved product, A to Z. For category tabs and the admin markup screen. */
    @GetMapping("/api/catalog/categories")
    List<String> categories() {
        return products.categories(Status.APPROVED);
    }

    @PostMapping("/api/shops/me/product-requests")
    @ResponseStatus(HttpStatus.CREATED)
    CatalogProduct request(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProductRequest req) {
        Long shopId = shopService.requireShop(jwt).getId();
        // Keeps one shop from flooding the admin review queue. A soft cap: two
        // parallel requests at the edge may both pass, which is harmless.
        if (products.countByRequestedByShopIdAndStatus(shopId, Status.PENDING) >= MAX_OPEN_REQUESTS_PER_SHOP) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many product requests waiting for review");
        }
        CatalogProduct p = new CatalogProduct();
        p.setName(req.name().trim());
        p.setBrand(blankToNull(req.brand()));
        p.setBarcode(blankToNull(req.barcode()));
        p.setCategory(req.category().trim());
        p.setTaxCategory(TaxCategory.TAXABLE); // safe default until admin reviews
        p.setStatus(Status.PENDING);
        p.setRequestedByShopId(shopId);
        return products.save(p);
    }

    /** Admin view of the catalog by status. ?status=PENDING is the review queue. */
    @GetMapping("/api/admin/catalog")
    Page<CatalogProduct> adminList(@RequestParam(defaultValue = "PENDING") Status status,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return products.search(status, q, Paging.of(page, size));
    }

    @PostMapping("/api/admin/catalog")
    @ResponseStatus(HttpStatus.CREATED)
    CatalogProduct adminCreate(@Valid @RequestBody AdminProductRequest req) {
        return products.save(apply(new CatalogProduct(), req));
    }

    /** Edits a product. Approving or rejecting a shop's request is this call with a new status. */
    @PutMapping("/api/admin/catalog/{id}")
    @Transactional
    CatalogProduct adminUpdate(@PathVariable Long id, @Valid @RequestBody AdminProductRequest req) {
        CatalogProduct p = products.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        // saveAndFlush so a duplicate barcode fails here, as a 409, not at commit.
        return products.saveAndFlush(apply(p, req));
    }

    private static CatalogProduct apply(CatalogProduct p, AdminProductRequest req) {
        p.setName(req.name().trim());
        p.setBrand(blankToNull(req.brand()));
        p.setBarcode(blankToNull(req.barcode()));
        p.setCategory(req.category().trim());
        p.setTaxCategory(req.taxCategory());
        p.setImageUrl(blankToNull(req.imageUrl()));
        p.setStatus(req.status());
        return p;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
