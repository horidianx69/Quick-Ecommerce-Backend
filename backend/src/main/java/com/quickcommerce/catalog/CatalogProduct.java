package com.quickcommerce.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One row per real-world product, shared by all shops. Admin owns this list. */
@Entity
@Table(name = "catalog_products")
@Getter
@Setter
public class CatalogProduct {

    public enum Status { PENDING, APPROVED, REJECTED }

    /** ZERO_RATED = basic groceries (no GST/HST). TAXABLE = everything else. */
    public enum TaxCategory { ZERO_RATED, TAXABLE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String brand;
    private String barcode;
    private String category;
    @Enumerated(EnumType.STRING)
    private TaxCategory taxCategory;
    private String imageUrl;
    @Enumerated(EnumType.STRING)
    private Status status;
    /** Set when a shop asked for this product; null when admin added it. */
    private Long requestedByShopId;
}
