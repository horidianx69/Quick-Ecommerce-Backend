package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fires many requests at the same moment at the same data. Each test states the
 * one outcome that must hold no matter how the threads interleave.
 */
class ConcurrencyTest extends ApiTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    com.zaxxer.hikari.HikariDataSource pool;

    @Test
    void parallelWrongPasswordsCannotBeatTheLoginLimit() {
        String email = newEmail();
        post("/api/auth/signup", null, signupJson(email, PASSWORD));

        List<Res> results = parallel(40, i -> post("/api/auth/login", null, loginJson(email, "wrong-password-" + i)));

        assertEquals(0, count(results, 200));
        assertTrue(count(results, 401) <= 5,
                "only 5 guesses may reach the password check, but " + count(results, 401) + " did");
        assertEquals(40, count(results, 401) + count(results, 429), "everything else is refused with 429");
    }

    @Test
    void parallelSignupsWithOneEmailCreateOneAccount() {
        String email = newEmail();
        List<Res> results = parallel(20, i -> post("/api/auth/signup", null, signupJson(email, PASSWORD)));

        assertEquals(1, count(results, 201), "exactly one signup wins");
        assertEquals(19, count(results, 409), "the rest are told the email is taken: " + statuses(results));
    }

    @Test
    void parallelShopCreatesMakeOneShop() {
        String token = signup();
        List<Res> results = parallel(12, i -> put("/api/shops/me", token, VALID_SHOP));

        assertTrue(count(results, 200) >= 1);
        assertEquals(12, count(results, 200) + count(results, 409), "no 5xx: " + statuses(results));
        long shopId = get("/api/shops/me", token).id();
        results.stream().filter(r -> r.status() == 200)
                .forEach(r -> assertEquals(shopId, r.id(), "every success is the same shop"));
        assertEquals(2, get("/api/shops/me", token).<List<?>>read("$.hours").size(), "hours are not duplicated");
    }

    @Test
    void parallelEditsOfOneShopAllSucceed() {
        String token = shopOwner();
        List<Res> results = parallel(12,
                i -> put("/api/shops/me", token, VALID_SHOP.replace("Corner Stop", "Corner Stop " + i)));

        assertEquals(12, count(results, 200), "a double-clicked Save must not fail: " + statuses(results));
        assertEquals(2, get("/api/shops/me", token).<List<?>>read("$.hours").size());
    }

    @Test
    void aShopEditCanNeverUndoAnAdminSuspension() {
        String admin = adminToken();
        String token = shopOwner();
        long shopId = get("/api/shops/me", token).id();

        for (int round = 0; round < 25; round++) {
            put("/api/admin/shops/" + shopId + "/status", admin, "{\"status\":\"APPROVED\"}");
            // The owner saves the profile on 6 threads while admin suspends the shop.
            // Each save changes the name, so each one really writes the shop row.
            int r = round;
            List<Res> results = parallel(7, i -> i == 0
                    ? put("/api/admin/shops/" + shopId + "/status", admin, "{\"status\":\"SUSPENDED\"}")
                    : put("/api/shops/me", token, VALID_SHOP.replace("Corner Stop", "Shop r" + r + " t" + i)));
            assertEquals(200, results.get(0).status(), results.get(0).body());
            assertEquals("SUSPENDED", get("/api/shops/me", token).read("$.status"),
                    "round " + round + ": the owner's save wrote a stale status over the suspension");
        }
    }

    @Test
    void parallelAddsOfOneProductCreateOneRow() {
        String admin = adminToken();
        String shop = shopOwner();
        long productId = approvedProduct(admin);

        List<Res> results = parallel(15, i -> post("/api/shops/me/products", shop, addJson(productId, 500, 1)));

        assertEquals(1, count(results, 201));
        assertEquals(14, count(results, 409), statuses(results));
        assertEquals(1, get("/api/shops/me/products", shop).<Integer>read("$.page.totalElements"));
    }

    @Test
    void stockChangesAndPriceEditsNeverLoseAnUpdate() {
        String admin = adminToken();
        String shop = shopOwner();
        long id = post("/api/shops/me/products", shop, addJson(approvedProduct(admin), 500, 0)).id();

        // 60 threads: even ones add 1 to stock, odd ones change the price.
        List<Res> results = parallel(60, i -> i % 2 == 0
                ? post("/api/shops/me/products/" + id + "/stock", shop, "{\"change\":1}")
                : put("/api/shops/me/products/" + id, shop, "{\"priceCents\":" + (500 + i) + "}"));

        assertEquals(60, count(results, 200), statuses(results));
        assertEquals(30, get("/api/shops/me/products", shop).<Integer>read("$.content[0].quantity"),
                "all 30 stock changes counted, no price edit wrote an old quantity back");
    }

    @Test
    void stockCanNeverGoBelowZero() {
        String admin = adminToken();
        String shop = shopOwner();
        long id = post("/api/shops/me/products", shop, addJson(approvedProduct(admin), 500, 10)).id();

        // 10 in stock, 40 threads each take 1: the same shape as orders racing for the last items.
        List<Res> results = parallel(40, i -> post("/api/shops/me/products/" + id + "/stock", shop, "{\"change\":-1}"));

        assertEquals(10, count(results, 200), "exactly 10 takes succeed: " + statuses(results));
        assertEquals(30, count(results, 409));
        assertEquals(0, get("/api/shops/me/products", shop).<Integer>read("$.content[0].quantity"));
    }

    @Test
    void stockCannotOverflow() {
        String admin = adminToken();
        String shop = shopOwner();
        long id = post("/api/shops/me/products", shop, addJson(approvedProduct(admin), 500, 1_000_000)).id();
        assertEquals(409, post("/api/shops/me/products/" + id + "/stock", shop, "{\"change\":1}").status(),
                "stock is capped at 1,000,000");
        assertEquals(400, post("/api/shops/me/products/" + id + "/stock", shop, "{\"change\":2147483647}").status());
    }

    @Test
    void mixedLoadFromManyShopsHasNoServerErrors() {
        String admin = adminToken();
        long productId = approvedProduct(admin);
        List<String> shops = parallel(8, i -> shopOwner());
        List<Long> rows = shops.stream()
                .map(s -> post("/api/shops/me/products", s, addJson(productId, 700, 5)).id()).toList();

        // 400 requests on 40 threads: reads, searches, stock changes and price edits across 8 shops.
        List<Res> results = parallel(40, t -> {
            Res worst = null;
            for (int n = 0; n < 10; n++) {
                int s = (t + n) % shops.size();
                Res res = switch ((t + n) % 5) {
                    case 0 -> get("/api/shops/me/products", shops.get(s));
                    case 1 -> get("/api/catalog?q=item", shops.get(s));
                    case 2 -> post("/api/shops/me/products/" + rows.get(s) + "/stock", shops.get(s), "{\"change\":1}");
                    case 3 -> put("/api/shops/me/products/" + rows.get(s), shops.get(s), "{\"priceCents\":" + (700 + n) + "}");
                    default -> get("/api/shops/me", shops.get(s));
                };
                if (worst == null || res.status() > worst.status()) {
                    worst = res;
                }
            }
            return worst;
        });

        assertEquals(40, count(results, 200), "every request answered 200: " + statuses(results));
        int totalStock = shops.stream()
                .mapToInt(s -> get("/api/shops/me/products", s).<Integer>read("$.content[0].quantity")).sum();
        assertEquals(8 * 5 + 80, totalStock, "80 stock changes were sent and all 80 are in the database");
    }

    @Test
    void whenTheDatabaseIsOutOfConnectionsRequestsGetAQuickBusyAnswer() throws Exception {
        String shop = shopOwner();
        // Take every connection in the pool, as a flood of slow queries would.
        List<java.sql.Connection> held = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < pool.getMaximumPoolSize(); i++) {
                held.add(pool.getConnection());
            }
            long start = System.nanoTime();
            Res res = get("/api/catalog", shop);
            long millis = (System.nanoTime() - start) / 1_000_000;

            assertEquals(503, res.status(), "busy, not broken: " + res.body());
            assertEquals("2", res.headers().firstValue("Retry-After").orElse(null));
            assertTrue(millis < 6000, "the request gave up after " + millis + " ms instead of hanging");
            assertTrue(!res.body().contains("Hikari") && !res.body().contains("Exception"), res.body());
        } finally {
            for (java.sql.Connection c : held) {
                c.close();
            }
        }
        assertEquals(200, get("/api/catalog", shop).status(), "and it recovers as soon as connections are free");
    }

    static String statuses(List<Res> results) {
        return results.stream().map(r -> String.valueOf(r.status())).sorted().distinct().toList()
                + " first error body: " + results.stream().filter(r -> r.status() >= 500).map(Res::body).findFirst().orElse("-");
    }
}
