package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Attacks the API the way a hostile client would. Every test must end in a clean refusal, never a 5xx. */
class SecurityTest extends ApiTestBase {

    // ----------------------------------------------------------- tokens

    @Test
    void forgedTokensAreRejected() {
        byte[] realKey = jwtSecret.getBytes(StandardCharsets.UTF_8);
        byte[] otherKey = "some-other-secret-that-is-32-bytes-long!".getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        String adminClaims = "{\"sub\":\"1\",\"scope\":\"ADMIN\",\"iat\":%d,\"exp\":%d}".formatted(now, now + 600);
        String expiredClaims = "{\"sub\":\"1\",\"scope\":\"ADMIN\",\"iat\":%d,\"exp\":%d}".formatted(now - 7200, now - 3600);

        // Control: a correctly signed token works, so the 401s below are real refusals.
        assertEquals(200, get("/api/admin/shops", mintToken("HS256", realKey, adminClaims)).status());

        assertEquals(401, get("/api/admin/shops", mintToken("none", null, adminClaims)).status(), "alg none");
        assertEquals(401, get("/api/admin/shops", mintToken("HS256", otherKey, adminClaims)).status(), "wrong key");
        assertEquals(401, get("/api/admin/shops", mintToken("HS512", realKey, adminClaims)).status(),
                "right key, algorithm we do not accept");
        assertEquals(401, get("/api/admin/shops", mintToken("HS256", realKey, expiredClaims)).status(), "expired");

        // Take a real SHOP token and swap its payload for an ADMIN one, keeping the signature.
        String[] parts = signup().split("\\.");
        String tampered = parts[0] + "." + b64(adminClaims.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertEquals(401, get("/api/admin/shops", tampered).status(), "tampered payload");

        assertEquals(401, get("/api/admin/shops", "").status());
        assertEquals(401, get("/api/admin/shops", "a.b.c").status());
    }

    @Test
    void tokenForAUserThatDoesNotExistGetsNothing() {
        long now = Instant.now().getEpochSecond();
        String ghost = mintToken("HS256", jwtSecret.getBytes(StandardCharsets.UTF_8),
                "{\"sub\":\"99999999\",\"scope\":\"SHOP\",\"iat\":%d,\"exp\":%d}".formatted(now, now + 600));
        assertEquals(401, get("/api/auth/me", ghost).status());
        assertEquals(404, get("/api/shops/me", ghost).status());
        assertEquals(404, get("/api/shops/me/products", ghost).status());
    }

    // ----------------------------------------------- hostile input values

    @Test
    void sqlInjectionStringsAreTreatedAsPlainText() {
        String shop = shopOwner();
        for (String q : List.of("'%20OR%20'1'='1", "';%20DROP%20TABLE%20users;%20--", "%25'%20UNION%20SELECT%20password_hash%20FROM%20users%20--",
                "%5C", "%00")) {
            Res res = get("/api/catalog?q=" + q, shop);
            assertTrue(res.status() == 200 || res.status() == 400, q + " -> " + res.status() + " " + res.body());
            assertFalse(res.body().contains("password"), "no data from other tables: " + res.body());
        }
        assertEquals(0, get("/api/catalog?q='%20OR%20'1'='1", shop).<Integer>read("$.page.totalElements"));

        Res sqlEmail = post("/api/auth/login", null, loginJson("x' OR '1'='1' --@a.test", "x' OR '1'='1"));
        assertEquals(401, sqlEmail.status());
        // The users table is still there and still works.
        assertEquals(201, post("/api/auth/signup", null, signupJson(newEmail(), PASSWORD)).status());
    }

    @Test
    void clientsCannotSetFieldsTheyDoNotOwn() {
        for (String role : List.of("ADMIN", "BOSS")) {
            Res refused = post("/api/auth/signup", null,
                    "{\"email\":\"%s\",\"password\":\"%s\",\"name\":\"x\",\"role\":\"%s\"}".formatted(newEmail(), PASSWORD, role));
            assertEquals(400, refused.status(), "nobody can sign themself up as " + role + ": " + refused.body());
        }
        Res signup = post("/api/auth/signup", null,
                "{\"email\":\"%s\",\"password\":\"%s\",\"name\":\"x\",\"id\":1}".formatted(newEmail(), PASSWORD));
        assertEquals(201, signup.status(), signup.body());
        assertEquals("SHOP", signup.read("$.user.role"), "no role means SHOP");
        String token = signup.read("$.token");
        assertEquals(403, get("/api/admin/shops", token).status());

        Res shop = put("/api/shops/me", token,
                VALID_SHOP.replace("{\"name\"", "{\"id\":1,\"ownerId\":1,\"status\":\"APPROVED\",\"name\""));
        assertEquals(200, shop.status(), shop.body());
        assertEquals("PENDING", shop.read("$.status"));
        assertEquals(signup.<Number>read("$.user.id").longValue(), shop.<Number>read("$.ownerId").longValue(),
                "ownerId comes from the token, not the body");

        Res request = post("/api/shops/me/product-requests", token,
                "{\"name\":\"Sneaky %s\",\"category\":\"x\",\"status\":\"APPROVED\",\"taxCategory\":\"ZERO_RATED\"}"
                        .formatted(UUID.randomUUID()));
        assertEquals(201, request.status(), request.body());
        assertEquals("PENDING", request.read("$.status"), "a shop cannot approve its own product request");
    }

    @Test
    void passwordsLongerThanBcryptCanHandleAreRefusedCleanly() {
        // 40 characters but 120 bytes: passes a character count, over BCrypt's 72-byte limit.
        String multibyte = "\u20AC".repeat(40);
        Res signup = post("/api/auth/signup", null, signupJson(newEmail(), multibyte));
        assertEquals(400, signup.status(), signup.body());

        String email = newEmail();
        post("/api/auth/signup", null, signupJson(email, PASSWORD));
        Res login = post("/api/auth/login", null, loginJson(email, "x".repeat(10_000)));
        assertTrue(login.status() == 400 || login.status() == 401, "got " + login.status() + " " + login.body());
        Res loginMultibyte = post("/api/auth/login", null, loginJson(email, multibyte));
        assertTrue(loginMultibyte.status() == 400 || loginMultibyte.status() == 401,
                "got " + loginMultibyte.status() + " " + loginMultibyte.body());

        assertEquals(400, post("/api/auth/signup", null, signupJson("a".repeat(300) + "@x.test", PASSWORD)).status(),
                "email length is bounded");
    }

    // ---------------------------------------------------- resource abuse

    @Test
    void oversizedAndUnsizedBodiesAreRefusedBeforeParsing() {
        String big = "{\"email\":\"a@b.test\",\"password\":\"" + "x".repeat(2_000_000) + "\"}";
        assertEquals(413, post("/api/auth/login", null, big).status(), "2 MB body");

        // No Content-Length (chunked): we cannot bound it up front, so we refuse it.
        byte[] small = loginJson("a@b.test", "whatever-1").getBytes(StandardCharsets.UTF_8);
        Res chunked = send("POST", "/api/auth/login", null,
                BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(small)));
        assertEquals(411, chunked.status(), chunked.body());
    }

    @Test
    void pagingCannotBeAbused() {
        String admin = adminToken();
        String shop = shopOwner();

        assertEquals(100, get("/api/admin/shops?size=100000", admin).<Integer>read("$.page.size"), "page size is capped");
        assertEquals(100, get("/api/catalog?size=100000", shop).<Integer>read("$.page.size"));
        assertEquals(200, get("/api/admin/shops?page=-5&size=-1", admin).status());

        for (String path : List.of("/api/admin/shops?sort=noSuchField", "/api/admin/shops?sort=id;drop%20table%20users",
                "/api/catalog?sort=noSuchField", "/api/admin/catalog?sort=sleep(10)", "/api/shops/me/products?sort=noSuchField")) {
            Res res = get(path, path.startsWith("/api/admin") ? admin : shop);
            assertTrue(res.status() == 200 || res.status() == 400, path + " -> " + res.status() + " " + res.body());
        }
    }

    @Test
    void aShopCannotFloodTheProductReviewQueue() {
        String shop = shopOwner();
        String tag = UUID.randomUUID().toString();
        List<Res> results = new java.util.ArrayList<>();
        for (int i = 0; i < 55; i++) {
            results.add(post("/api/shops/me/product-requests", shop,
                    "{\"name\":\"Spam %s %d\",\"category\":\"x\"}".formatted(tag, i)));
        }
        assertEquals(50, count(results, 201), "50 open requests per shop");
        assertEquals(5, count(results, 429));
    }

    // -------------------------------------------------- error behaviour

    @Test
    void errorsNeverLeakInternals() {
        String admin = adminToken();
        String shop = shopOwner();
        List<Res> errors = List.of(
                put("/api/admin/shops/abc/status", admin, "{\"status\":\"APPROVED\"}"),
                put("/api/admin/shops/1/status", admin, "{\"status\":\"NOPE\"}"),
                put("/api/shops/me", shop, "{not json"),
                put("/api/shops/me", shop, "[]"),
                put("/api/shops/me", shop, VALID_SHOP.replace("49.19", "\"north\"")),
                post("/api/shops/me/products", shop, "{\"productId\":\"x\",\"priceCents\":1e99,\"quantity\":1}"),
                post("/api/shops/me/products", shop, "{\"productId\":9223372036854775808,\"priceCents\":5,\"quantity\":1}"),
                put("/api/shops/me/products/99999999999999999999", shop, "{\"priceCents\":5}"),
                get("/api/nope", shop),
                call("PATCH", "/api/shops/me", shop, "{}"),
                call("TRACE", "/api/shops/me", shop, null));
        for (Res res : errors) {
            assertTrue(res.status() >= 400 && res.status() < 500, "expected a 4xx, got " + res.status() + " " + res.body());
            for (String leak : List.of("Exception", "org.springframework", "org.hibernate", "com.quickcommerce",
                    "SQL", "jdbc", "\tat ")) {
                assertFalse(res.body().contains(leak), "response leaks '" + leak + "': " + res.body());
            }
        }
    }

    @Test
    void responsesCarrySafeHeaders() {
        Res res = get("/api/catalog", shopOwner());
        assertEquals("nosniff", res.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("DENY", res.headers().firstValue("X-Frame-Options").orElse(null));
        assertTrue(res.headers().firstValue("Cache-Control").orElse("").contains("no-store"),
                "API answers must not be cached by browsers or proxies");

        // A site that is not on the allow-list gets no CORS permission.
        var evil = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", BodyPublishers.noBody()).build();
        try {
            var preflight = http.send(evil, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertTrue(preflight.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
