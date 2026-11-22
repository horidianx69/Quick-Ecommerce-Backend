package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Drives the running app over real HTTP against a real Postgres, the same way
 * the frontend will. Each test makes its own users, so tests do not depend on
 * each other.
 */
class ShopBackendTest extends ApiTestBase {

    // ---------------------------------------------------------------- auth

    @Test
    void signupLoginAndMe() {
        String email = newEmail();
        Res signup = post("/api/auth/signup", null, signupJson(email, PASSWORD));
        assertEquals(201, signup.status());
        assertEquals("SHOP", signup.read("$.user.role"));
        assertFalse(signup.body().toLowerCase().contains("hash"), "password hash must never be returned");

        assertEquals(409, post("/api/auth/signup", null, signupJson(email, PASSWORD)).status());
        assertEquals(409, post("/api/auth/signup", null, signupJson(email.toUpperCase(), PASSWORD)).status(),
                "email is case-insensitive");
        assertEquals(400, post("/api/auth/signup", null, signupJson(newEmail(), "short")).status());
        assertEquals(400, post("/api/auth/signup", null, signupJson("not-an-email", PASSWORD)).status());

        assertEquals(401, post("/api/auth/login", null, loginJson(email, "wrong-password")).status());
        assertEquals(401, post("/api/auth/login", null, loginJson(newEmail(), PASSWORD)).status());

        Res login = post("/api/auth/login", null, loginJson(email, PASSWORD));
        assertEquals(200, login.status());
        Res me = get("/api/auth/me", login.read("$.token"));
        assertEquals(200, me.status());
        assertEquals(email, me.read("$.email"));
    }

    @Test
    void loginIsBlockedAfterFiveWrongPasswords() {
        String email = newEmail();
        post("/api/auth/signup", null, signupJson(email, PASSWORD));
        for (int i = 0; i < 5; i++) {
            assertEquals(401, post("/api/auth/login", null, loginJson(email, "wrong-password")).status());
        }
        assertEquals(429, post("/api/auth/login", null, loginJson(email, PASSWORD)).status(),
                "even the right password is refused while blocked");
    }

    @Test
    void routesAreProtectedByRole() {
        String shop = signup();
        String admin = adminToken();

        assertEquals(401, get("/api/catalog", null).status());
        assertEquals(401, get("/api/catalog", "not-a-real-token").status());
        assertEquals(401, get("/api/shops/me", null).status());
        assertEquals(401, get("/api/admin/shops", null).status());

        assertEquals(403, get("/api/admin/shops", shop).status());
        assertEquals(403, get("/api/admin/catalog", shop).status());
        assertEquals(403, put("/api/admin/shops/1/status", shop, "{\"status\":\"APPROVED\"}").status());
        assertEquals(403, get("/api/shops/me", admin).status());

        assertEquals(200, get("/api/catalog", shop).status());
        assertEquals(200, get("/api/admin/shops", admin).status());
    }

    // ---------------------------------------------------------------- shop

    @Test
    void shopOnboardingAndApproval() {
        String shop = signup();
        String admin = adminToken();
        assertEquals(404, get("/api/shops/me", shop).status(), "no shop yet");

        assertEquals(400, put("/api/shops/me", shop, VALID_SHOP.replace("v3t 1a1", "12345")).status());
        assertEquals(400, put("/api/shops/me", shop, VALID_SHOP.replace("49.19", "200")).status());
        assertEquals(400, put("/api/shops/me", shop, VALID_SHOP.replace("\"BC\"", "\"XX\"")).status());
        Res empty = put("/api/shops/me", shop, "{}");
        assertEquals(400, empty.status());
        assertNotNull(empty.read("$.errors.name"), "validation errors name each bad field: " + empty.body());
        assertNotNull(empty.read("$.errors.postalCode"));

        Res created = put("/api/shops/me", shop, VALID_SHOP);
        assertEquals(200, created.status(), created.body());
        assertEquals("PENDING", created.read("$.status"));
        assertEquals("V3T 1A1", created.read("$.postalCode"));
        assertEquals("America/Vancouver", created.read("$.timeZone"), "time zone follows the province");
        assertEquals(0, created.<List<?>>read("$.hours").size(), "no hours until admin sets them");
        long shopId = created.id();

        // Opening hours are admin's: hours sent by the owner are ignored.
        Res ownHours = put("/api/shops/me", shop, VALID_SHOP.replace("}",
                ",\"hours\":[{\"dayOfWeek\":\"MONDAY\",\"opens\":\"09:00\",\"closes\":\"21:00\"}]}"));
        assertEquals(200, ownHours.status(), ownHours.body());
        assertEquals(0, ownHours.<List<?>>read("$.hours").size());
        assertEquals("America/Toronto", put("/api/shops/me", shop, VALID_SHOP.replace("\"BC\"", "\"ON\"").replace("v3t 1a1", "M5V 2T6"))
                .read("$.timeZone"), "moving province moves the time zone");

        // An owner cannot approve themself: a status in the body is ignored.
        String edited = VALID_SHOP.replace("Corner Stop", "Corner Stop 2")
                .replace("{\"name\"", "{\"status\":\"APPROVED\",\"name\"");
        Res updated = put("/api/shops/me", shop, edited);
        assertEquals(200, updated.status(), updated.body());
        assertEquals(shopId, updated.id(), "second PUT updates, it does not create a second shop");
        assertEquals("Corner Stop 2", updated.read("$.name"));
        assertEquals("PENDING", updated.read("$.status"));

        Res pending = get("/api/admin/shops?status=PENDING&size=200", admin);
        assertTrue(pending.<List<?>>read("$.content[?(@.id == " + shopId + ")]").size() == 1);

        Res approved = put("/api/admin/shops/" + shopId + "/status", admin, "{\"status\":\"APPROVED\"}");
        assertEquals(200, approved.status());
        assertEquals("APPROVED", get("/api/shops/me", shop).read("$.status"));
        assertTrue(get("/api/admin/shops?status=PENDING&size=200", admin)
                .<List<?>>read("$.content[?(@.id == " + shopId + ")]").isEmpty());

        assertEquals(404, put("/api/admin/shops/999999/status", admin, "{\"status\":\"APPROVED\"}").status());
        assertEquals(400, put("/api/admin/shops/" + shopId + "/status", admin, "{\"status\":\"NOPE\"}").status());
    }

    // ------------------------------------------------------------- catalog

    @Test
    void catalogSearchRequestAndApproval() {
        String shop = shopOwner();
        String admin = adminToken();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String barcode = "0" + Math.abs(tag.hashCode());

        Res created = post("/api/admin/catalog", admin, productJson("Maple Syrup " + tag, barcode, "APPROVED"));
        assertEquals(201, created.status(), created.body());
        assertEquals(409, post("/api/admin/catalog", admin, productJson("Copy " + tag, barcode, "APPROVED")).status(),
                "barcode is unique");
        assertEquals(400, post("/api/admin/catalog", admin, "{\"name\":\"\"}").status());

        assertEquals(1, totalFound(shop, "maple syrup " + tag), "search ignores case");
        assertEquals(1, totalFound(shop, barcode), "search by exact barcode");

        Res requested = post("/api/shops/me/product-requests", shop,
                "{\"name\":\"Poutine Sauce " + tag + "\",\"brand\":\"\",\"category\":\"Pantry\"}");
        assertEquals(201, requested.status(), requested.body());
        assertEquals("PENDING", requested.read("$.status"));
        long requestedId = requested.id();
        assertEquals(0, totalFound(shop, "Poutine Sauce " + tag), "pending products are hidden from search");
        assertEquals(1, get("/api/admin/catalog?status=PENDING&q=Poutine%20Sauce%20" + tag, admin)
                .<Integer>read("$.page.totalElements"));

        Res approve = put("/api/admin/catalog/" + requestedId, admin,
                productJson("Poutine Sauce " + tag, null, "APPROVED"));
        assertEquals(200, approve.status(), approve.body());
        assertEquals(1, totalFound(shop, "Poutine Sauce " + tag));

        assertEquals(409, put("/api/admin/catalog/" + requestedId, admin,
                productJson("Poutine Sauce " + tag, barcode, "APPROVED")).status(), "barcode clash on edit");
        assertEquals(404, put("/api/admin/catalog/999999", admin, productJson("x", null, "APPROVED")).status());
    }

    // ------------------------------------------------- shop products, stock

    @Test
    void shopProductsAndStock() {
        String shop = shopOwner();
        String admin = adminToken();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long productId = post("/api/admin/catalog", admin, productJson("Milk 2L " + tag, null, "APPROVED")).id();
        long pendingId = post("/api/admin/catalog", admin, productJson("Hidden " + tag, null, "PENDING")).id();

        assertEquals(400, post("/api/shops/me/products", shop, addJson(pendingId, 599, 1)).status(), "not approved");
        assertEquals(400, post("/api/shops/me/products", shop, addJson(999999, 599, 1)).status(), "unknown product");
        assertEquals(400, post("/api/shops/me/products", shop, addJson(productId, 0, 1)).status(), "price must be > 0");
        assertEquals(400, post("/api/shops/me/products", shop, addJson(productId, 599, -1)).status(), "no negative stock");

        Res added = post("/api/shops/me/products", shop, addJson(productId, 599, 10));
        assertEquals(201, added.status(), added.body());
        assertEquals("Milk 2L " + tag, added.read("$.name"));
        assertEquals(10, added.<Integer>read("$.quantity"));
        long id = added.id();
        assertEquals(409, post("/api/shops/me/products", shop, addJson(productId, 599, 10)).status(), "same product twice");

        Res list = get("/api/shops/me/products", shop);
        assertEquals(1, list.<Integer>read("$.page.totalElements"));
        assertEquals(599, list.<Integer>read("$.content[0].priceCents"));

        Res updated = put("/api/shops/me/products/" + id, shop, "{\"priceCents\":649,\"quantity\":999}");
        assertEquals(200, updated.status(), updated.body());
        assertEquals(649, updated.<Integer>read("$.priceCents"));
        assertEquals(10, updated.<Integer>read("$.quantity"), "a price edit never changes stock");
        assertEquals(400, put("/api/shops/me/products/" + id, shop, "{\"priceCents\":0}").status());

        Res restocked = post("/api/shops/me/products/" + id + "/stock", shop, "{\"change\":6}");
        assertEquals(200, restocked.status(), restocked.body());
        assertEquals(16, restocked.<Integer>read("$.quantity"));
        Res corrected = post("/api/shops/me/products/" + id + "/stock", shop, "{\"change\":-4}");
        assertEquals(12, corrected.<Integer>read("$.quantity"), "a negative change is a stock correction");
        assertEquals(400, post("/api/shops/me/products/" + id + "/stock", shop, "{\"change\":0}").status());
        assertEquals(409, post("/api/shops/me/products/" + id + "/stock", shop, "{\"change\":-13}").status(),
                "stock cannot go below zero");
        assertEquals(12, get("/api/shops/me/products", shop).<Integer>read("$.content[0].quantity"));

        assertEquals(204, delete("/api/shops/me/products/" + id, shop).status());
        assertEquals(404, delete("/api/shops/me/products/" + id, shop).status());
        assertEquals(0, get("/api/shops/me/products", shop).<Integer>read("$.page.totalElements"));
    }

    @Test
    void aShopCannotSeeOrChangeAnotherShopsProducts() {
        String admin = adminToken();
        String shopA = shopOwner();
        String shopB = shopOwner();
        long productId = post("/api/admin/catalog", admin,
                productJson("Eggs " + UUID.randomUUID(), null, "APPROVED")).id();
        long aRow = post("/api/shops/me/products", shopA, addJson(productId, 450, 12)).id();

        assertEquals(404, put("/api/shops/me/products/" + aRow, shopB, "{\"priceCents\":1}").status());
        assertEquals(404, post("/api/shops/me/products/" + aRow + "/stock", shopB, "{\"change\":5}").status());
        assertEquals(404, delete("/api/shops/me/products/" + aRow, shopB).status());
        assertEquals(0, get("/api/shops/me/products", shopB).<Integer>read("$.page.totalElements"));

        Res aList = get("/api/shops/me/products", shopA);
        assertEquals(450, aList.<Integer>read("$.content[0].priceCents"), "A's row is untouched");
        assertEquals(12, aList.<Integer>read("$.content[0].quantity"));

        assertEquals(201, post("/api/shops/me/products", shopB, addJson(productId, 500, 3)).status(),
                "two shops can sell the same catalog product");

        String noShopYet = signup();
        assertEquals(404, get("/api/shops/me/products", noShopYet).status());
        assertEquals(404, post("/api/shops/me/products", noShopYet, addJson(productId, 500, 3)).status());
    }

    // ------------------------------------------------------------ contract

    @Test
    void openApiContractIsPublishedWithoutLogin() {
        Res docs = get("/v3/api-docs", null);
        assertEquals(200, docs.status());
        for (String path : List.of("/api/auth/signup", "/api/auth/login", "/api/shops/me",
                "/api/shops/me/products", "/api/shops/me/products/{id}/stock", "/api/catalog",
                "/api/admin/shops/{id}/status", "/api/admin/catalog/{id}",
                "/api/orders", "/api/orders/{id}/cancel", "/api/shops/me/orders", "/api/shops/me/orders/{id}/accept",
                "/api/shops/me/orders/{id}/reject", "/api/shops/me/orders/{id}/ready",
                "/api/admin/settings", "/api/admin/settings/markups/{category}", "/api/admin/orders", "/api/admin/orders/{id}/cancel",
                "/api/admin/orders/{orderId}/assign", "/api/admin/orders/{orderId}/unassign", "/api/admin/shops/{id}/hours",
                "/api/admin/users", "/api/admin/users/{id}/disabled", "/api/admin/stats", "/api/catalog/categories")) {
            assertTrue(docs.body().contains("\"" + path + "\""), "missing in contract: " + path);
        }
    }
}
