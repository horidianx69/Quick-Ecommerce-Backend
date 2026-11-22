package com.quickcommerce.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Every method takes shopId, so one shop can never reach another shop's rows.
 *
 * Rows are only ever changed by the single-column statements below, never by
 * loading an entity and saving it back. That is what keeps a price edit from
 * writing an old stock count over a change made at the same moment.
 */
public interface ShopProductRepository extends JpaRepository<ShopProduct, Long> {

    int MAX_QUANTITY = 1_000_000;

    String VIEW = """
            select new com.quickcommerce.catalog.ShopProductView(
                sp.id, p.id, p.name, p.brand, p.imageUrl, p.category, sp.priceCents, sp.quantity)
            from ShopProduct sp join CatalogProduct p on p.id = sp.productId
            where sp.shopId = :shopId
            """;

    @Query(value = VIEW + " order by p.name, sp.id",
            countQuery = "select count(sp) from ShopProduct sp where sp.shopId = :shopId")
    Page<ShopProductView> list(Long shopId, Pageable pageable);

    @Query(VIEW + " and sp.id = :id")
    Optional<ShopProductView> view(Long shopId, Long id);

    @Query(VIEW + " and sp.id in :ids")
    List<ShopProductView> viewsIn(Long shopId, Collection<Long> ids);

    boolean existsByIdAndShopId(Long id, Long shopId);

    @Modifying
    @Query("update ShopProduct sp set sp.quantity = 0 where sp.id = :id and sp.shopId = :shopId")
    int setOutOfStock(Long shopId, Long id);

    @Modifying
    @Query("update ShopProduct sp set sp.priceCents = :priceCents where sp.id = :id and sp.shopId = :shopId")
    int setPrice(Long shopId, Long id, int priceCents);

    /**
     * Adds change (which may be negative) to the count in one statement. Changes
     * no row, and returns 0, if the result would leave 0..MAX_QUANTITY. The
     * database takes a row lock for the statement, so parallel calls queue up
     * and stock can never go below zero. Orders will take stock this same way.
     */
    @Modifying
    @Query("update ShopProduct sp set sp.quantity = sp.quantity + :change"
            + " where sp.id = :id and sp.shopId = :shopId"
            + " and sp.quantity + :change between 0 and " + MAX_QUANTITY)
    int changeStock(Long shopId, Long id, int change);

    long deleteByIdAndShopId(Long id, Long shopId);
}
