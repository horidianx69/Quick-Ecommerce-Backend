package com.quickcommerce.browse;

import com.quickcommerce.common.Geo;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The read side for customers: what can I get near me. Plain SQL across the
 * shop and catalog tables, read-only, one statement per call. Each call takes
 * a database connection only for that one statement and keeps no state, so it
 * is safe at any level of parallel use.
 *
 * Only APPROVED shops, APPROVED products and rows with stock ever come back.
 */
// ponytail: distance is a bounding-box index lookup plus the haversine formula
// in SQL. Good for thousands of shops. Move to PostGIS (GiST index) when the
// shop count makes the box scan show up in query times.
// ponytail: every feed request reads all in-stock rows of all shops in range
// (10,000 rows = about 20 ms). No cache yet. When feed traffic or shop density
// makes that hurt, cache pages per map cell for a few seconds, or keep a
// best-offer-per-product table up to date instead of computing it per request.
@Repository
class BrowseQueries {

    /** Where the customer is and how far we look. The box is a cheap first cut; km is the exact test. */
    record Area(double lat, double lng, double maxKm) {
        double latSpan() {
            return maxKm / 111.0;
        }

        double lngSpan() {
            return maxKm / (111.32 * Math.max(0.01, Math.cos(Math.toRadians(lat))));
        }
    }

    record OfferRow(long productId, String name, String brand, String imageUrl, String category,
            long shopProductId, long shopId, int shopPriceCents, double km, int shops) {}

    record ShopOfferRow(long shopProductId, long shopId, int shopPriceCents, double km) {}

    /** Kilometres from the customer to a shop's PUBLIC point (see Shop.publicLatitude). */
    private static final String KM = Geo.kmSql("s.public_latitude", "s.public_longitude", ":lat", ":lng");

    /**
     * "materialized" pins the order of work: first the nearby shops, then (in
     * FEED) their in-stock shelf rows, and only then the catalog join. Without
     * it Postgres is free to reorder, and for a query with bound parameters it
     * settled on pairing every nearby shop with every catalog product: 800 ms
     * where this order takes 20 ms. Measured with 60 shops and 10,000 rows in range.
     */
    private static final String NEARBY = """
            with nearby as materialized (
                select id, km from (
                    select s.id, %s as km
                    from shops s
                    where s.status = 'APPROVED'
                      and s.public_latitude between :minLat and :maxLat
                      and s.public_longitude between :minLng and :maxLng
                ) boxed
                where km <= :maxKm
            )
            """.formatted(KM);

    /**
     * One row per product: the offer from the nearest shop that has it (ties go
     * to the lower price), plus how many nearby shops sell it. With a pinned
     * shop, only that shop's products come back, each as that shop's offer.
     */
    private static final String FEED = NEARBY + """
            , shelf as materialized (
                select sp.id as shop_product_id, sp.shop_id, sp.product_id, sp.price_cents, n.km
                from nearby n
                join shop_products sp on sp.shop_id = n.id and sp.quantity > 0
            ), offers as (
                select p.id as product_id, p.name, p.brand, p.image_url, p.category,
                       x.shop_product_id, x.shop_id, x.price_cents, x.km,
                       row_number() over (partition by p.id
                           order by (x.shop_id = :shopId) desc, x.km, x.price_cents, x.shop_product_id) as pick,
                       count(*) over (partition by p.id) as shops
                from shelf x
                join catalog_products p on p.id = x.product_id and p.status = 'APPROVED'
                where (:pattern = '' or lower(p.name) like :pattern or lower(coalesce(p.brand, '')) like :pattern)
                  and (:category = '' or p.category = :category)
            )
            select * from offers
            where pick = 1 and (:shopId = 0 or shop_id = :shopId)
            order by km, name, product_id
            limit :limit offset :offset
            """;

    private static final String OFFERS = NEARBY + """
            select sp.id as shop_product_id, sp.shop_id, sp.price_cents, n.km
            from nearby n
            join shop_products sp on sp.shop_id = n.id and sp.product_id = :productId and sp.quantity > 0
            join catalog_products p on p.id = sp.product_id and p.status = 'APPROVED'
            order by n.km, sp.price_cents, sp.id
            limit 50
            """;

    private static final String SHOP_DISTANCE = NEARBY + "select km from nearby where id = :shopId";

    private final JdbcClient jdbc;

    BrowseQueries(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * @param pinnedShopId 0 = best offer from any nearby shop
     * @param pattern      "" = no search, else a LIKE pattern already lower-cased and escaped
     * @param category     "" = any
     */
    List<OfferRow> feed(Area area, long pinnedShopId, String pattern, String category, int limit, long offset) {
        return in(area, FEED)
                .param("shopId", pinnedShopId)
                .param("pattern", pattern)
                .param("category", category)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, row) -> new OfferRow(rs.getLong("product_id"), rs.getString("name"), rs.getString("brand"),
                        rs.getString("image_url"), rs.getString("category"), rs.getLong("shop_product_id"),
                        rs.getLong("shop_id"), rs.getInt("price_cents"), rs.getDouble("km"), rs.getInt("shops")))
                .list();
    }

    /** Every nearby shop that has this product in stock, nearest first. */
    List<ShopOfferRow> offers(Area area, long productId) {
        return in(area, OFFERS)
                .param("productId", productId)
                .query((rs, row) -> new ShopOfferRow(rs.getLong("shop_product_id"), rs.getLong("shop_id"),
                        rs.getInt("price_cents"), rs.getDouble("km")))
                .list();
    }

    /** Distance to the shop, or empty if it is not approved or not within range. */
    Optional<Double> shopDistanceKm(Area area, long shopId) {
        return in(area, SHOP_DISTANCE).param("shopId", shopId).query(Double.class).optional();
    }

    private JdbcClient.StatementSpec in(Area area, String sql) {
        return jdbc.sql(sql)
                .param("lat", area.lat())
                .param("lng", area.lng())
                .param("maxKm", area.maxKm())
                .param("minLat", area.lat() - area.latSpan())
                .param("maxLat", area.lat() + area.latSpan())
                .param("minLng", area.lng() - area.lngSpan())
                .param("maxLng", area.lng() + area.lngSpan());
    }
}
