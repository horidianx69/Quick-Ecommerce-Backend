package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the whole app on a random port against a real Postgres and gives the
 * tests plain HTTP helpers, so they hit the API the way a client does.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(EmbeddedDb.class)
abstract class ApiTestBase {

    static final String PASSWORD = "correct-horse-1";
    static final String VALID_SHOP = """
            {"name":"Corner Stop","phone":"+1 604 555 0100","addressLine":"12 Main St","city":"Surrey",
             "province":"BC","postalCode":"v3t 1a1","latitude":49.19,"longitude":-122.85}
            """;

    @Value("${local.server.port}")
    int port;
    @Value("${app.admin.email}")
    String adminEmail;
    @Value("${app.admin.password}")
    String adminPassword;
    @Value("${app.jwt.secret}")
    String jwtSecret;

    final HttpClient http = HttpClient.newHttpClient();

    record Res(int status, String body, HttpHeaders headers) {
        <T> T read(String jsonPath) {
            return JsonPath.read(body, jsonPath);
        }

        long id() {
            return this.<Number>read("$.id").longValue();
        }
    }

    Res get(String path, String token) {
        return call("GET", path, token, null);
    }

    Res post(String path, String token, String json) {
        return call("POST", path, token, json);
    }

    Res put(String path, String token, String json) {
        return call("PUT", path, token, json);
    }

    Res delete(String path, String token) {
        return call("DELETE", path, token, null);
    }

    Res call(String method, String path, String token, String json) {
        return send(method, path, token, json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json));
    }

    Res send(String method, String path, String token, BodyPublisher body) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body);
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        try {
            var res = http.send(req.build(), BodyHandlers.ofString());
            return new Res(res.statusCode(), res.body(), res.headers());
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + path + " failed", e);
        }
    }

    /** Runs the task n times at the same moment (all threads wait at a gate) and returns every result. */
    <T> List<T> parallel(int n, IntFunction<T> task) {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.apply(index);
                }));
            }
            gate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    static long count(List<Res> results, int status) {
        return results.stream().filter(r -> r.status() == status).count();
    }

    static String newEmail() {
        return "owner-" + UUID.randomUUID() + "@shop.test";
    }

    static String signupJson(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\",\"name\":\"Test Owner\"}".formatted(email, password);
    }

    static String loginJson(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    static String productJson(String name, String barcode, String status) {
        return "{\"name\":\"%s\",\"brand\":\"Acme\",\"barcode\":%s,\"category\":\"Grocery\",\"taxCategory\":\"ZERO_RATED\",\"status\":\"%s\"}"
                .formatted(name, barcode == null ? "null" : "\"" + barcode + "\"", status);
    }

    static String addJson(long productId, int priceCents, int quantity) {
        return "{\"productId\":%d,\"priceCents\":%d,\"quantity\":%d}".formatted(productId, priceCents, quantity);
    }

    /** Signs up a fresh SHOP user and returns its token. */
    String signup() {
        Res res = post("/api/auth/signup", null, signupJson(newEmail(), PASSWORD));
        assertEquals(201, res.status(), res.body());
        return res.read("$.token");
    }

    /** A fresh SHOP user that has already created its shop. */
    String shopOwner() {
        String token = signup();
        Res res = put("/api/shops/me", token, VALID_SHOP);
        assertEquals(200, res.status(), res.body());
        return token;
    }

    String adminToken() {
        Res res = post("/api/auth/login", null, loginJson(adminEmail, adminPassword));
        assertEquals(200, res.status(), res.body());
        String token = res.read("$.token");
        assertNotNull(token);
        return token;
    }

    // ------------------------------------------------------ order helpers

    /** About 1.3 km from VALID_SHOP, so inside the delivery range. */
    static final String NEAR_ADDRESS = "{\"addressLine\":\"77 Customer Rd\",\"city\":\"Surrey\",\"province\":\"BC\","
            + "\"postalCode\":\"V3T 2B2\",\"latitude\":49.2,\"longitude\":-122.86}";

    /** shop = the owner's token, shopId = the shop, rowId = its one stocked shop product. */
    record Stocked(String shop, long shopId, long rowId) {}

    /** Signs up a fresh CUSTOMER and returns its token. */
    String customer() {
        Res res = post("/api/auth/signup", null,
                "{\"email\":\"%s\",\"password\":\"%s\",\"name\":\"Test Customer\",\"role\":\"CUSTOMER\"}"
                        .formatted(newEmail(), PASSWORD));
        assertEquals(201, res.status(), res.body());
        return res.read("$.token");
    }

    /** An approved shop with one product in stock. */
    Stocked stockedShop(String admin, int priceCents, int quantity) {
        String shop = shopOwner();
        long shopId = get("/api/shops/me", shop).id();
        assertEquals(200, put("/api/admin/shops/" + shopId + "/status", admin, "{\"status\":\"APPROVED\"}").status());
        openAllWeek(admin, shopId);
        return new Stocked(shop, shopId, addStock(shop, admin, priceCents, quantity));
    }

    /** Adds one more product to the shop; returns the shop product id. */
    long addStock(String shop, String admin, int priceCents, int quantity) {
        Res res = post("/api/shops/me/products", shop, addJson(approvedProduct(admin), priceCents, quantity));
        assertEquals(201, res.status(), res.body());
        return res.id();
    }

    int stockOf(String shop, long rowId) {
        List<Integer> found = get("/api/shops/me/products?size=100", shop).read("$.content[?(@.id == " + rowId + ")].quantity");
        assertEquals(1, found.size(), "shop product " + rowId + " not found");
        return found.get(0);
    }

    static String item(long rowId, int quantity) {
        return "{\"shopProductId\":%d,\"quantity\":%d}".formatted(rowId, quantity);
    }

    /** An order body with a fresh idempotency key and the nearby address. */
    static String orderJson(long shopId, String... items) {
        return orderJsonWith(shopId, UUID.randomUUID().toString(), NEAR_ADDRESS, items);
    }

    static String orderJsonWith(long shopId, String key, String address, String... items) {
        return "{\"shopId\":%d,\"idempotencyKey\":\"%s\",\"address\":%s,\"items\":[%s]}"
                .formatted(shopId, key, address, String.join(",", items));
    }

    /** Places an order that must succeed; returns the response. */
    Res order(String customer, long shopId, String... items) {
        Res res = post("/api/orders", customer, orderJson(shopId, items));
        assertEquals(201, res.status(), res.body());
        return res;
    }

    // ----------------------------------------------------- browse helpers

    /** The customer's spot in browse tests: the same point as NEAR_ADDRESS. */
    static final String HERE = "lat=49.2&lng=-122.86";

    /** An approved shop whose REAL location is the given point. Returns the owner's token and the shop id. */
    Stocked approvedShopAt(String admin, double lat, double lng) {
        String shop = signup();
        Res res = put("/api/shops/me", shop, VALID_SHOP
                .replace("\"latitude\":49.19", "\"latitude\":" + lat).replace("\"longitude\":-122.85", "\"longitude\":" + lng));
        assertEquals(200, res.status(), res.body());
        assertEquals(200, put("/api/admin/shops/" + res.id() + "/status", admin, "{\"status\":\"APPROVED\"}").status());
        openAllWeek(admin, res.id());
        return new Stocked(shop, res.id(), 0);
    }

    /** Every day 00:00 to 23:59:59, so the shop is open whenever a test runs. */
    static final String ALL_WEEK = "{\"hours\":[" + String.join(",", java.util.Arrays.stream(java.time.DayOfWeek.values())
            .map(d -> "{\"dayOfWeek\":\"" + d + "\",\"opens\":\"00:00\",\"closes\":\"23:59:59\"}").toList()) + "]}";

    void openAllWeek(String admin, long shopId) {
        Res res = put("/api/admin/shops/" + shopId + "/hours", admin, ALL_WEEK);
        assertEquals(200, res.status(), res.body());
    }

    /** A new approved catalog product with this exact name; returns its id. */
    long product(String admin, String name) {
        Res res = post("/api/admin/catalog", admin, productJson(name, null, "APPROVED"));
        assertEquals(201, res.status(), res.body());
        return res.id();
    }

    /** Puts a catalog product on a shop's shelf; returns the shop product id. */
    long stock(String shop, long productId, int priceCents, int quantity) {
        Res res = post("/api/shops/me/products", shop, addJson(productId, priceCents, quantity));
        assertEquals(201, res.status(), res.body());
        return res.id();
    }

    // ----------------------------------------------------- driver helpers

    record DriverAt(String token, long userId) {}

    /**
     * A shop, a customer and a spot on the map that no other test uses, so the
     * driver's job list holds only this test's orders.
     */
    record Scene(String admin, String shop, long shopId, long rowId, String customer, double lat, double lng) {}

    Scene scene(int stockQuantity) {
        String admin = adminToken();
        double lat = 52 + Math.random() * 6;
        double lng = -112 + Math.random() * 6;
        Stocked shop = approvedShopAt(admin, lat, lng);
        long row = stock(shop.shop(), product(admin, "Secret Product " + UUID.randomUUID()), 1000, stockQuantity);
        return new Scene(admin, shop.shop(), shop.shopId(), row, customer(), lat, lng);
    }

    static String position(double lat, double lng) {
        return "{\"latitude\":%s,\"longitude\":%s}".formatted(lat, lng);
    }

    /** An approved driver, online at the given point. */
    DriverAt driverAt(String admin, double lat, double lng) {
        Res signup = post("/api/auth/signup", null,
                "{\"email\":\"%s\",\"password\":\"%s\",\"name\":\"Dana Driver\",\"role\":\"DRIVER\"}".formatted(newEmail(), PASSWORD));
        assertEquals(201, signup.status(), signup.body());
        String token = signup.read("$.token");
        long userId = signup.<Number>read("$.user.id").longValue();
        assertEquals(200, put("/api/drivers/me", token, "{\"phone\":\"+1 604 555 0142\"}").status());
        assertEquals(200, put("/api/admin/drivers/" + userId + "/status", admin, "{\"status\":\"APPROVED\"}").status());
        Res online = post("/api/drivers/me/online", token, position(lat, lng));
        assertEquals(200, online.status(), online.body());
        return new DriverAt(token, userId);
    }

    /** An approved, online driver about 0.6 km from the scene's shop. */
    DriverAt driverNear(Scene s) {
        return driverAt(s.admin(), s.lat() + 0.004, s.lng() - 0.004);
    }

    /** A delivery address about 1.3 km from the scene's shop. */
    static String doorNear(Scene s) {
        return "{\"addressLine\":\"9 Door St\",\"city\":\"Doorville\",\"province\":\"AB\",\"postalCode\":\"T2P 1J9\","
                + "\"latitude\":%s,\"longitude\":%s}".formatted(s.lat() + 0.01, s.lng() - 0.01);
    }

    /** The scene's customer orders `units` of the product and the shop accepts. Returns the order id. */
    long acceptedOrder(Scene s, String customer, int units) {
        Res placed = post("/api/orders", customer, orderJsonWith(s.shopId(), UUID.randomUUID().toString(), doorNear(s),
                item(s.rowId(), units)).replace("\"items\"", "\"note\":\"Gate code 4411\",\"items\""));
        assertEquals(201, placed.status(), placed.body());
        assertEquals(200, post("/api/shops/me/orders/" + placed.id() + "/accept", s.shop(), null).status());
        return placed.id();
    }

    /** A new approved catalog product; returns its id. */
    long approvedProduct(String admin) {
        Res res = post("/api/admin/catalog", admin, productJson("Item " + UUID.randomUUID(), null, "APPROVED"));
        assertEquals(201, res.status(), res.body());
        return res.id();
    }

    int totalFound(String token, String q) {
        Res res = get("/api/catalog?q=" + q.replace(" ", "%20"), token);
        assertEquals(200, res.status(), res.body());
        return res.<Integer>read("$.page.totalElements");
    }

    /** Hand-builds a JWT so tests can forge tokens. alg "none" gets an empty signature. */
    static String mintToken(String alg, byte[] key, String payloadJson) {
        try {
            String header = "{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}";
            String signingInput = b64(header.getBytes(StandardCharsets.UTF_8))
                    + "." + b64(payloadJson.getBytes(StandardCharsets.UTF_8));
            if (alg.equals("none")) {
                return signingInput + ".";
            }
            String macName = "HmacSHA" + alg.substring(2);
            Mac mac = Mac.getInstance(macName);
            mac.init(new SecretKeySpec(key, macName));
            return signingInput + "." + b64(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
