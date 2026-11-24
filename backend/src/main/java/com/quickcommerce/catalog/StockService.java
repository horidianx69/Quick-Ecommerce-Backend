package com.quickcommerce.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

/**
 * The door the order part uses to take stock and give it back.
 *
 * MANDATORY: every call must be inside the caller's transaction, so taking
 * stock and writing the order succeed or fail together.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class StockService {

    private final ShopProductRepository shopProducts;

    StockService(ShopProductRepository shopProducts) {
        this.shopProducts = shopProducts;
    }

    /**
     * Takes stock for every line, or throws and (through the caller's rollback)
     * takes none. Returns the products with the prices that now apply: each row
     * stays locked until the transaction ends, so a price cannot change in between.
     *
     * @param quantities shop product id -> units wanted
     */
    public List<ShopProductView> reserve(Long shopId, Map<Long, Integer> quantities) {
        // Ascending id order: two orders for the same products always lock the
        // rows in the same order, so they can never deadlock each other.
        for (var line : new TreeMap<>(quantities).entrySet()) {
            Long id = line.getKey();
            if (shopProducts.reserve(shopId, id, line.getValue()) == 0) {
                if (!shopProducts.approvedViewsIn(shopId, List.of(id)).isEmpty()) {
                    throw problem(HttpStatus.CONFLICT, "Not enough stock", id);
                }
                throw unavailable(shopId, id);
            }
        }
        return shopProducts.approvedViewsIn(shopId, quantities.keySet());
    }

    /** The shop's products with today's price and stock, without taking anything. For price quotes. */
    @Transactional(readOnly = true)
    public List<ShopProductView> peek(Long shopId, Collection<Long> shopProductIds) {
        List<ShopProductView> found = shopProducts.approvedViewsIn(shopId, shopProductIds);
        if (found.size() != shopProductIds.size()) {
            var foundIds = found.stream().map(ShopProductView::id).collect(Collectors.toSet());
            Long missing = shopProductIds.stream().filter(id -> !foundIds.contains(id)).min(Long::compareTo).orElseThrow();
            throw unavailable(shopId, missing);
        }
        return found;
    }

    private ErrorResponseException unavailable(Long shopId, Long id) {
        return shopProducts.existsByIdAndShopId(id, shopId)
                ? problem(HttpStatus.CONFLICT, "Product is no longer available", id)
                : problem(HttpStatus.BAD_REQUEST, "Product is not sold by this shop", id);
    }

    /** Gives units back. Does nothing if the shop has removed the product since. */
    public void restore(Long shopId, Long shopProductId, int quantity) {
        shopProducts.changeStock(shopId, shopProductId, quantity);
    }

    /** The shop said it does not have this product after all: the count on file was wrong. */
    public void markOutOfStock(Long shopId, Long shopProductId) {
        shopProducts.setOutOfStock(shopId, shopProductId);
    }

    /** The error carries shopProductId so the app can point at the exact cart line. */
    private static ErrorResponseException problem(HttpStatus status, String detail, Long shopProductId) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("shopProductId", shopProductId);
        return new ErrorResponseException(status, body, null);
    }
}
