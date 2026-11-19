package com.quickcommerce.browse;

import com.quickcommerce.browse.BrowseQueries.Area;
import com.quickcommerce.browse.BrowseQueries.OfferRow;
import com.quickcommerce.common.Geo;
import com.quickcommerce.common.Paging;
import com.quickcommerce.order.Pricing;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Product-first browsing for customers. lat/lng is the customer's delivery
 * point. A shop is shown only as an id, a distance and a time: never its name,
 * address or coordinates.
 */
@RestController
@RequestMapping("/api/browse")
public class BrowseController {

    static final int MAX_SEARCH_LENGTH = 60;

    /** What to put in the cart: send shopId + shopProductId to POST /api/orders. priceCents is the customer's price. */
    public record Offer(long shopProductId, long shopId, int priceCents, double distanceKm, int etaMinutes) {}

    /** offer = the nearest shop that has it. otherShops = how many more nearby shops also have it. */
    public record ProductCard(long productId, String name, String brand, String imageUrl, String category,
            Offer offer, int otherShops) {}

    /** hasMore = ask for page + 1. There is no total count: a feed scrolls, and counting would double the work. */
    public record Feed(List<ProductCard> content, int page, int size, boolean hasMore) {}

    public record ShopInfo(long shopId, double distanceKm, int etaMinutes) {}

    public record ShopFeed(ShopInfo shop, List<ProductCard> content, int page, int size, boolean hasMore) {}

    private final BrowseQueries queries;
    private final Pricing pricing;
    private final double maxDistanceKm;
    private final int prepMinutes;
    private final double minutesPerKm;

    BrowseController(BrowseQueries queries, Pricing pricing,
            @Value("${app.orders.max-distance-km}") double maxDistanceKm,
            @Value("${app.browse.prep-minutes}") int prepMinutes,
            @Value("${app.browse.minutes-per-km}") double minutesPerKm) {
        this.queries = queries;
        this.pricing = pricing;
        this.maxDistanceKm = maxDistanceKm;
        this.prepMinutes = prepMinutes;
        this.minutesPerKm = minutesPerKm;
    }

    /** The home feed and search: each product once, from the nearest shop that has it in stock. */
    @GetMapping("/products")
    Feed products(@RequestParam double lat, @RequestParam double lng,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        PageRequest paging = Paging.of(page, size);
        List<ProductCard> cards = cards(area(lat, lng), 0, q, category, paging);
        return new Feed(trim(cards, paging), paging.getPageNumber(), paging.getPageSize(), cards.size() > paging.getPageSize());
    }

    /** "Change shop" for one product: every nearby shop that has it, nearest first. */
    @GetMapping("/products/{productId}/offers")
    List<Offer> offers(@PathVariable long productId, @RequestParam double lat, @RequestParam double lng) {
        return queries.offers(area(lat, lng), productId).stream()
                .map(row -> offer(row.shopProductId(), row.shopId(), row.shopPriceCents(), row.km()))
                .toList();
    }

    /** Browse and search inside one shop. 404 if the shop is not open to this customer (not approved, or too far). */
    @GetMapping("/shops/{shopId}/products")
    ShopFeed shopProducts(@PathVariable long shopId, @RequestParam double lat, @RequestParam double lng,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Area area = area(lat, lng);
        double km = queries.shopDistanceKm(area, shopId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop not found"));
        PageRequest paging = Paging.of(page, size);
        List<ProductCard> cards = cards(area, shopId, q, category, paging);
        return new ShopFeed(new ShopInfo(shopId, roundKm(km), eta(km)), trim(cards, paging),
                paging.getPageNumber(), paging.getPageSize(), cards.size() > paging.getPageSize());
    }

    /** Asks for one row more than the page holds; that extra row is how hasMore is known without a count query. */
    private List<ProductCard> cards(Area area, long pinnedShopId, String q, String category, PageRequest paging) {
        if (category.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "category is too long");
        }
        return queries.feed(area, pinnedShopId, likePattern(q), category.trim(), paging.getPageSize() + 1, paging.getOffset())
                .stream().map(this::card).toList();
    }

    private static List<ProductCard> trim(List<ProductCard> cards, PageRequest paging) {
        return cards.size() > paging.getPageSize() ? cards.subList(0, paging.getPageSize()) : cards;
    }

    private ProductCard card(OfferRow row) {
        return new ProductCard(row.productId(), row.name(), row.brand(), row.imageUrl(), row.category(),
                offer(row.shopProductId(), row.shopId(), row.shopPriceCents(), row.km()), row.shops() - 1);
    }

    private Offer offer(long shopProductId, long shopId, int shopPriceCents, double km) {
        return new Offer(shopProductId, shopId, pricing.customerPrice(shopPriceCents), roundKm(km), eta(km));
    }

    // ponytail: a fixed prep time plus a fixed speed. Replace with real numbers
    // (per-shop prep time, driver availability) once deliveries produce data.
    private int eta(double km) {
        return prepMinutes + (int) Math.ceil(km * minutesPerKm);
    }

    /** One decimal place is all a customer needs, and it gives away less than the exact figure. */
    private static double roundKm(double km) {
        return Math.round(km * 10) / 10.0;
    }

    private Area area(double lat, double lng) {
        if (!Geo.isValid(lat, lng)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lat must be -90..90 and lng -180..180");
        }
        return new Area(lat, lng, maxDistanceKm);
    }

    /** "" for no search. Otherwise %text% with LIKE's own wildcards escaped, so "100%" means the text 100%. */
    private static String likePattern(String q) {
        String term = q.trim().toLowerCase();
        if (term.length() > MAX_SEARCH_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q is too long");
        }
        if (term.isEmpty()) {
            return "";
        }
        return "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
