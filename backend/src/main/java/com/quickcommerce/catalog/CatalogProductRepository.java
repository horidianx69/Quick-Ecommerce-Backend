package com.quickcommerce.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CatalogProductRepository extends JpaRepository<CatalogProduct, Long> {

    // ponytail: LIKE '%x%' scans the table. Fine for a few thousand products;
    // add a pg_trgm GIN index (or full-text search) when search gets slow.
    @Query(value = """
            select p from CatalogProduct p
            where p.status = :status
              and (lower(p.name) like :pattern or lower(coalesce(p.brand, '')) like :pattern or p.barcode = :q)
            order by p.name, p.id
            """,
            countQuery = """
            select count(p) from CatalogProduct p
            where p.status = :status
              and (lower(p.name) like :pattern or lower(coalesce(p.brand, '')) like :pattern or p.barcode = :q)
            """)
    Page<CatalogProduct> search(CatalogProduct.Status status, String pattern, String q, Pageable pageable);

    long countByRequestedByShopIdAndStatus(Long shopId, CatalogProduct.Status status);

    /** Name, brand or exact barcode match. Empty q returns everything with that status. */
    default Page<CatalogProduct> search(CatalogProduct.Status status, String q, Pageable pageable) {
        String term = q.trim();
        return search(status, "%" + term.toLowerCase() + "%", term, pageable);
    }
}
