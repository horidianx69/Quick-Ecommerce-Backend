package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Customer browsing. The customer stands at HERE (49.2, -122.86). Shops are
 * placed at known real distances; reported distances may be off by up to
 * 0.4 km because customers only ever see a shop's shifted public point.
 * Every test searches by its own unique tag, so other tests' data never shows up.
 */
class BrowseTest extends ApiTestBase {

    /** Real distances from HERE: NEAR about 1.3 km, MID about 4.4 km, FAR about 33 km (outside the 10 km range). */
    static final double[] NEAR = {49.19, -122.85};
    static final double[] MID = {49.24, -122.86};
    static final double[] FAR = {49.50, -122.86};

    static String tag() {
        return "zq" + UUID.randomUUID().toString().substring(0, 8);
    }

    Res feed(String customer, String query) {
        Res res = get("/api/browse/products?" + HERE + "&" + query, customer);
        assertEquals(200, res.status(), res.body());
        return res;
    }

    @Test
    void theFeedShowsEachProductOnceFromTheNearestShop() {
        String admin = adminToken();
        String tag = tag();
        long milk = product(admin, "Milk " + tag);
        Stocked near = approvedShopAt(admin, NEAR[0], NEAR[1]);
        Stocked mid = approvedShopAt(admin, MID[0], MID[1]);
        Stocked far = approvedShopAt(admin, FAR[0], FAR[1]);
        long nearRow = stock(near.shop(), milk, 1000, 5);
        stock(mid.shop(), milk, 800, 5);
        stock(far.shop(), milk, 500, 5);

        Res feed = feed(customer(), "q=" + tag);
        assertEquals(1, feed.<List<?>>read("$.content").size(), "one card per product, not one per shop");
        assertEquals("Milk " + tag, feed.read("$.content[0].name"));
        assertEquals((int) near.shopId(), feed.<Integer>read("$.content[0].offer.shopId"), "the nearest shop wins");
        assertEquals((int) nearRow, feed.<Integer>read("$.content[0].offer.shopProductId"));
        assertEquals(1500, feed.<Integer>read("$.content[0].offer.priceCents"), "customer price = shop price 1000 x 1.5");
        assertEquals(1, feed.<Integer>read("$.content[0].otherShops"), "the mid shop also has it; the far shop is out of range");
        assertFalse(feed.<Boolean>read("$.hasMore"));

        double km = feed.<Number>read("$.content[0].offer.distanceKm").doubleValue();
        assertTrue(km >= 0.8 && km <= 1.8, "about 1.3 km, got " + km);
        assertEquals(10 + (int) Math.ceil(km * 3), feed.<Integer>read("$.content[0].offer.etaMinutes"), 1,
                "10 min prep + 3 min per km");

        // Nothing about who or where the shop is, and nothing about the shop's own price or stock count.
        for (String secret : List.of("Corner Stop", "12 Main St", "latitude", "longitude", "ownerId", "phone",
                "postalCode", "quantity", "\"priceCents\":1000", "shopPriceCents")) {
            assertFalse(feed.body().contains(secret), "feed leaks " + secret + ": " + feed.body());
        }
    }

    @Test
    void aCustomerCanSeeEveryShopForAProductAndSwitch() {
        String admin = adminToken();
        long eggs = product(admin, "Eggs " + tag());
        Stocked near = approvedShopAt(admin, NEAR[0], NEAR[1]);
        Stocked mid = approvedShopAt(admin, MID[0], MID[1]);
        Stocked far = approvedShopAt(admin, FAR[0], FAR[1]);
        stock(near.shop(), eggs, 1000, 5);
        long midRow = stock(mid.shop(), eggs, 800, 5);
        stock(far.shop(), eggs, 500, 5);
        String customer = customer();

        Res offers = get("/api/browse/products/" + eggs + "/offers?" + HERE, customer);
        assertEquals(200, offers.status(), offers.body());
        assertEquals(List.of((int) near.shopId(), (int) mid.shopId()), offers.<List<Integer>>read("$[*].shopId"),
                "nearest first, and the out-of-range shop is not offered");
        assertEquals(List.of(1500, 1200), offers.<List<Integer>>read("$[*].priceCents"));
        double midKm = offers.<Number>read("$[1].distanceKm").doubleValue();
        assertTrue(midKm >= 3.9 && midKm <= 4.9, "about 4.4 km, got " + midKm);

        // The price shown is the price charged: order from the second shop and compare.
        Res placed = order(customer, mid.shopId(), item(midRow, 1));
        assertEquals(1200, placed.<Integer>read("$.items[0].unitPriceCents"));

        assertEquals(List.of(), get("/api/browse/products/999999999/offers?" + HERE, customer).<List<?>>read("$"));
    }

    @Test
    void outOfStockUnapprovedAndOutOfRangeAreNeverShown() {
        String admin = adminToken();
        String tag = tag();
        long bread = product(admin, "Bread " + tag);
        Stocked near = approvedShopAt(admin, NEAR[0], NEAR[1]);
        Stocked mid = approvedShopAt(admin, MID[0], MID[1]);
        Stocked far = approvedShopAt(admin, FAR[0], FAR[1]);
        long nearRow = stock(near.shop(), bread, 1000, 5);
        stock(mid.shop(), bread, 800, 5);
        stock(far.shop(), bread, 500, 5);
        String pendingOwner = shopOwner(); // at the NEAR spot, never approved
        stock(pendingOwner, bread, 100, 5);
        String customer = customer();

        assertEquals((int) near.shopId(), feed(customer, "q=" + tag).<Integer>read("$.content[0].offer.shopId"));

        post("/api/shops/me/products/" + nearRow + "/stock", near.shop(), "{\"change\":-5}");
        Res afterSellOut = feed(customer, "q=" + tag);
        assertEquals((int) mid.shopId(), afterSellOut.<Integer>read("$.content[0].offer.shopId"),
                "the near shop sold out, so the next nearest takes over");
        assertEquals(0, afterSellOut.<Integer>read("$.content[0].otherShops"));

        put("/api/admin/shops/" + mid.shopId() + "/status", admin, "{\"status\":\"SUSPENDED\"}");
        assertEquals(0, feed(customer, "q=" + tag).<List<?>>read("$.content").size(),
                "a suspended shop disappears at once; the pending and far shops were never shown");

        // A customer standing next to the far shop sees that shop, and only that one.
        Res farFeed = get("/api/browse/products?lat=49.5&lng=-122.86&q=" + tag, customer);
        assertEquals(List.of((int) far.shopId()), farFeed.<List<Integer>>read("$.content[*].offer.shopId"));
    }

    @Test
    void insideOneShopOnlyThatShopsProductsShow() {
        String admin = adminToken();
        String tag = tag();
        long tea = product(admin, "Tea " + tag);
        long jam = product(admin, "Jam " + tag);
        Stocked near = approvedShopAt(admin, NEAR[0], NEAR[1]);
        Stocked mid = approvedShopAt(admin, MID[0], MID[1]);
        Stocked far = approvedShopAt(admin, FAR[0], FAR[1]);
        stock(near.shop(), tea, 1000, 5);
        long midTea = stock(mid.shop(), tea, 800, 5);
        stock(mid.shop(), jam, 600, 5);
        stock(far.shop(), tea, 500, 5);
        String customer = customer();
        String inMid = "/api/browse/shops/" + mid.shopId() + "/products?" + HERE;

        Res shop = get(inMid + "&q=" + tag, customer);
        assertEquals(200, shop.status(), shop.body());
        assertEquals(2, shop.<List<?>>read("$.content").size());
        assertEquals(Set.of((int) mid.shopId()), new HashSet<>(shop.<List<Integer>>read("$.content[*].offer.shopId")),
                "every card is this shop's own offer, even where another shop is nearer");
        assertEquals(List.of((int) midTea), shop.<List<Integer>>read("$.content[?(@.productId == " + tea + ")].offer.shopProductId"));
        assertEquals(List.of(1), shop.<List<Integer>>read("$.content[?(@.productId == " + tea + ")].otherShops"),
                "the near shop has tea too");
        assertEquals(List.of(0), shop.<List<Integer>>read("$.content[?(@.productId == " + jam + ")].otherShops"));
        assertEquals((int) mid.shopId(), shop.<Integer>read("$.shop.shopId"));
        assertTrue(shop.<Number>read("$.shop.distanceKm").doubleValue() >= 3.9);
        assertTrue(shop.<Integer>read("$.shop.etaMinutes") >= 20);
        assertFalse(shop.body().contains("Corner Stop"), "no shop name here either");

        assertEquals(List.of("Jam " + tag), get(inMid + "&q=jam%20" + tag, customer).<List<String>>read("$.content[*].name"),
                "search inside the shop");
        assertEquals(0, get(inMid + "&q=nothing-like-this-" + tag, customer).<List<?>>read("$.content").size());

        assertEquals(404, get("/api/browse/shops/" + far.shopId() + "/products?" + HERE, customer).status(), "out of range");
        long pendingShop = get("/api/shops/me", shopOwner()).id();
        assertEquals(404, get("/api/browse/shops/" + pendingShop + "/products?" + HERE, customer).status(), "not approved");
        assertEquals(404, get("/api/browse/shops/999999999/products?" + HERE, customer).status());
    }

    @Test
    void theFeedPagesWithoutRepeatsOrGaps() {
        String admin = adminToken();
        String tag = tag();
        Stocked near = approvedShopAt(admin, NEAR[0], NEAR[1]);
        for (String name : List.of("Apple", "Banana", "Cherry", "Date", "Elderberry")) {
            stock(near.shop(), product(admin, name + " " + tag), 300, 5);
        }
        String customer = customer();

        Res first = feed(customer, "q=" + tag + "&size=2&page=0");
        Res second = feed(customer, "q=" + tag + "&size=2&page=1");
        Res third = feed(customer, "q=" + tag + "&size=2&page=2");
        assertTrue(first.<Boolean>read("$.hasMore"));
        assertTrue(second.<Boolean>read("$.hasMore"));
        assertFalse(third.<Boolean>read("$.hasMore"));
        assertEquals(List.of("Apple " + tag, "Banana " + tag), first.<List<String>>read("$.content[*].name"));
        assertEquals(List.of("Cherry " + tag, "Date " + tag), second.<List<String>>read("$.content[*].name"));
        assertEquals(List.of("Elderberry " + tag), third.<List<String>>read("$.content[*].name"));
        assertEquals(0, feed(customer, "q=" + tag + "&size=2&page=3").<List<?>>read("$.content").size());

        assertEquals(100, feed(customer, "size=100000").<Integer>read("$.size"), "page size is capped");
        assertEquals(1, feed(customer, "q=" + tag + "&category=Grocery&size=1").<List<?>>read("$.content").size());
        assertEquals(0, feed(customer, "q=" + tag + "&category=Nope").<List<?>>read("$.content").size());
    }

    // ---------------------------------------------------------- security

    @Test
    void aShopsRealLocationCannotBeWorkedOutFromDistances() {
        String admin = adminToken();
        String tag = tag();
        Stocked near = approvedShopAt(admin, NEAR[0], NEAR[1]);
        stock(near.shop(), product(admin, "Salt " + tag), 300, 5);
        String customer = customer();

        // Standing exactly on the shop's real spot does not read 0.0 km:
        // distances are measured from a point 150 to 400 m away from it.
        Res onTop = get("/api/browse/products?lat=" + NEAR[0] + "&lng=" + NEAR[1] + "&q=" + tag, customer);
        double km = onTop.<Number>read("$.content[0].offer.distanceKm").doubleValue();
        assertTrue(km >= 0.1 && km <= 0.5, "expected 0.1 to 0.5 km, got " + km);

        // The shifted point stays put when the owner saves again without moving,
        // so it cannot be averaged out by watching it change.
        List<String> probes = List.of("lat=49.2&lng=-122.86", "lat=49.17&lng=-122.9", "lat=49.23&lng=-122.8");
        List<Double> before = probes.stream().map(p -> get("/api/browse/products?" + p + "&q=" + tag, customer)
                .<Number>read("$.content[0].offer.distanceKm").doubleValue()).toList();
        for (int i = 0; i < 5; i++) {
            assertEquals(200, put("/api/shops/me", near.shop(), VALID_SHOP.replace("Corner Stop", "Renamed " + i)).status());
        }
        List<Double> after = probes.stream().map(p -> get("/api/browse/products?" + p + "&q=" + tag, customer)
                .<Number>read("$.content[0].offer.distanceKm").doubleValue()).toList();
        assertEquals(before, after);
    }

    @Test
    void badInputIsRefusedAndOnlyCustomersCanBrowse() {
        String admin = adminToken();
        String tag = tag();
        Stocked near = approvedShopAt(admin, NEAR[0], NEAR[1]);
        stock(near.shop(), product(admin, "Rice " + tag), 300, 5);
        String customer = customer();
        String base = "/api/browse/products";

        for (String bad : List.of("", "?lat=49.2", "?lng=-122.86", "?lat=abc&lng=1", "?lat=NaN&lng=-122.86",
                "?lat=49.2&lng=Infinity", "?lat=91&lng=0", "?lat=0&lng=181", "?" + HERE + "&q=" + "x".repeat(61),
                "?" + HERE + "&category=" + "x".repeat(81), "?" + HERE + "&page=abc")) {
            assertEquals(400, get(base + bad, customer).status(), "expected 400 for " + bad);
        }
        assertEquals(400, get("/api/browse/products/abc/offers?" + HERE, customer).status());
        assertEquals(400, get("/api/browse/shops/1/products?lat=NaN&lng=1", customer).status());

        // Search text is plain text: LIKE wildcards and SQL are not special.
        for (String q : List.of("%25", "_", "%25" + tag, "'%20OR%20'1'='1", "';%20DROP%20TABLE%20shops;%20--")) {
            Res res = get(base + "?" + HERE + "&q=" + q, customer);
            assertEquals(200, res.status(), q + " -> " + res.body());
            assertEquals(0, res.<List<?>>read("$.content").size(), "'" + q + "' must match nothing: " + res.body());
        }
        assertEquals(1, feed(customer, "q=RICE%20" + tag.toUpperCase()).<List<?>>read("$.content").size(), "search ignores case");

        for (String path : List.of(base + "?" + HERE, "/api/browse/products/1/offers?" + HERE,
                "/api/browse/shops/" + near.shopId() + "/products?" + HERE)) {
            assertEquals(401, get(path, null).status(), path);
            assertEquals(403, get(path, near.shop()).status(), "a shop cannot browse other shops: " + path);
            assertEquals(403, get(path, admin).status(), path);
        }
    }

    // -------------------------------------------------------- under load

    @Test
    void theFeedStaysCorrectWhileTheLastItemsSellOut() {
        String admin = adminToken();
        String tag = tag();
        Stocked near = approvedShopAt(admin, NEAR[0], NEAR[1]);
        long row = stock(near.shop(), product(admin, "Butter " + tag), 400, 10);
        List<String> customers = parallel(30, i -> customer());

        // 30 customers order the last 10 units while 30 more threads keep reading the feed.
        List<Res> results = parallel(60, i -> i < 30
                ? post("/api/orders", customers.get(i), orderJson(near.shopId(), item(row, 1)))
                : get("/api/browse/products?" + HERE + "&q=" + tag, customers.get(i - 30)));

        List<Res> orders = results.subList(0, 30);
        List<Res> reads = results.subList(30, 60);
        assertEquals(10, count(orders, 201), ConcurrencyTest.statuses(orders));
        assertEquals(20, count(orders, 409));
        assertEquals(30, count(reads, 200), "every read during the rush answered: " + ConcurrencyTest.statuses(reads));
        assertEquals(0, feed(customers.get(0), "q=" + tag).<List<?>>read("$.content").size(),
                "sold out, so it is gone from the feed");
    }
}
