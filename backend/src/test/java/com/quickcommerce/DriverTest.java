package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The driver side over real HTTP: account, job list, the delivery steps, and what each party may see. */
class DriverTest extends ApiTestBase {

    @Autowired
    DataSource dataSource;

    static final String PHONE = "{\"phone\":\"+1 604 555 0142\"}";

    String newDriverLogin() {
        Res res = post("/api/auth/signup", null,
                "{\"email\":\"%s\",\"password\":\"%s\",\"name\":\"Dana Driver\",\"role\":\"DRIVER\"}".formatted(newEmail(), PASSWORD));
        assertEquals(201, res.status(), res.body());
        assertEquals("DRIVER", res.read("$.user.role"));
        return res.read("$.token");
    }

    @Test
    void aDriverSignsUpIsApprovedAndGoesOnline() {
        String admin = adminToken();
        String driver = newDriverLogin();
        long userId = get("/api/auth/me", driver).id();
        String here = position(49.2, -122.86);

        assertEquals(404, get("/api/drivers/me", driver).status(), "no profile yet");
        assertEquals(404, post("/api/drivers/me/online", driver, here).status());
        assertEquals(400, put("/api/drivers/me", driver, "{\"phone\":\"call me\"}").status());

        Res profile = put("/api/drivers/me", driver, PHONE);
        assertEquals(200, profile.status(), profile.body());
        assertEquals("PENDING", profile.read("$.status"));
        assertEquals(409, post("/api/drivers/me/online", driver, here).status(), "not approved yet");
        assertEquals(409, get("/api/drivers/me/jobs", driver).status());

        // A driver cannot approve themself: status in the body is ignored.
        assertEquals("PENDING", put("/api/drivers/me", driver, "{\"phone\":\"+1 604 555 0143\",\"status\":\"APPROVED\",\"online\":true}").read("$.status"));

        Res queue = get("/api/admin/drivers?status=PENDING&size=100", admin);
        assertEquals(List.of("Dana Driver"), queue.<List<String>>read("$.content[?(@.userId == " + userId + ")].name"));
        assertEquals("APPROVED", put("/api/admin/drivers/" + userId + "/status", admin, "{\"status\":\"APPROVED\"}").read("$.status"));
        assertEquals(404, put("/api/admin/drivers/999999999/status", admin, "{\"status\":\"APPROVED\"}").status());

        assertEquals(409, post("/api/drivers/me/location", driver, here).status(), "not online yet");
        for (String bad : List.of("{}", "{\"latitude\":91,\"longitude\":0}", "{\"latitude\":0,\"longitude\":-181}", "{\"latitude\":\"x\",\"longitude\":0}")) {
            assertEquals(400, post("/api/drivers/me/online", driver, bad).status(), bad);
        }
        Res online = post("/api/drivers/me/online", driver, here);
        assertEquals(200, online.status(), online.body());
        assertTrue(online.<Boolean>read("$.online"));

        assertEquals(204, post("/api/drivers/me/location", driver, position(49.21, -122.87)).status());
        assertEquals(49.21, get("/api/drivers/me", driver).<Number>read("$.latitude").doubleValue(), 0.0001, "the ping moved the driver");
        assertEquals(400, post("/api/drivers/me/location", driver, "{\"latitude\":999,\"longitude\":0}").status());
        assertEquals(200, get("/api/drivers/me/jobs", driver).status());

        assertFalse(post("/api/drivers/me/offline", driver, null).<Boolean>read("$.online"));
        assertEquals(409, post("/api/drivers/me/location", driver, here).status(), "offline drivers are not tracked");
        assertEquals(409, get("/api/drivers/me/jobs", driver).status());

        // A suspended driver is taken offline at once and cannot come back.
        post("/api/drivers/me/online", driver, here);
        put("/api/admin/drivers/" + userId + "/status", admin, "{\"status\":\"SUSPENDED\"}");
        assertFalse(get("/api/drivers/me", driver).<Boolean>read("$.online"));
        assertEquals(409, post("/api/drivers/me/online", driver, here).status());
        assertEquals(409, post("/api/drivers/me/location", driver, here).status());
    }

    @Test
    void aDeliveryFromJobListToDoorstep() {
        Scene s = scene(10);
        long orderId = acceptedOrder(s, s.customer(), 3);
        DriverAt driver = driverNear(s);
        String productName = get("/api/orders/" + orderId, s.customer()).read("$.items[0].name");

        // 1. The job list: distances, unit count and pay. Nothing that identifies anyone.
        Res jobs = get("/api/drivers/me/jobs", driver.token());
        assertEquals(200, jobs.status(), jobs.body());
        assertEquals(1, jobs.<List<?>>read("$").size());
        assertEquals((int) orderId, jobs.<Integer>read("$[0].orderId"));
        assertEquals(3, jobs.<Integer>read("$[0].units"), "how many units, not what they are");
        int estimate = jobs.<Integer>read("$[0].payCents");
        assertTrue(estimate > 0 && estimate < 2000, "an estimate of hourly pay plus km for about 2 km, got " + estimate);
        double pickupKm = jobs.<Number>read("$[0].pickupKm").doubleValue();
        double dropKm = jobs.<Number>read("$[0].dropKm").doubleValue();
        assertTrue(pickupKm > 0.2 && pickupKm < 1.2, "driver is about 0.6 km from the shop, got " + pickupKm);
        assertTrue(dropKm > 0.8 && dropKm < 2.0, "shop to door is about 1.3 km, got " + dropKm);
        for (String secret : List.of(productName, "Secret Product", "Corner Stop", "12 Main St", "9 Door St", "Gate code",
                "latitude", "Test Customer", "deliveryCode")) {
            assertFalse(jobs.body().contains(secret), "job list leaks " + secret + ": " + jobs.body());
        }

        // 2. Accept: now the shop's name, address and real location, for navigation. Still no customer details.
        Res accepted = post("/api/drivers/me/jobs/" + orderId + "/accept", driver.token(), null);
        assertEquals(200, accepted.status(), accepted.body());
        assertEquals("Corner Stop", accepted.read("$.pickup.name"));
        assertEquals("12 Main St", accepted.read("$.pickup.addressLine"));
        assertEquals(s.lat(), accepted.<Number>read("$.pickup.latitude").doubleValue(), 1e-9, "the real location, not the shifted public one");
        assertEquals(s.lng(), accepted.<Number>read("$.pickup.longitude").doubleValue(), 1e-9);
        assertNull(accepted.read("$.dropoff"), "the customer's address stays hidden until pickup");
        assertNull(accepted.read("$.note"));
        assertEquals("Doorville", accepted.read("$.dropoffCity"));
        for (String secret : List.of(productName, "Secret Product", "9 Door St", "Gate code", "Test Customer", "deliveryCode")) {
            assertFalse(accepted.body().contains(secret), "accepted view leaks " + secret + ": " + accepted.body());
        }
        assertEquals(0, get("/api/drivers/me/jobs", driver.token()).<List<?>>read("$").size(), "one delivery at a time");
        assertTrue(get("/api/shops/me/orders/" + orderId, s.shop()).<Boolean>read("$.driverAssigned"));
        assertTrue(get("/api/orders/" + orderId, s.customer()).<Boolean>read("$.driverAssigned"));
        String riderName = get("/api/auth/me", driver.token()).read("$.name");
        assertEquals(riderName, get("/api/orders/" + orderId, s.customer()).read("$.driverName"), "the customer sees who is bringing it");

        // 3. Pickup is possible only once the shop says the order is ready.
        assertEquals(409, post("/api/drivers/me/delivery/picked-up", driver.token(), null).status(), "shop is still packing");
        assertEquals(409, post("/api/drivers/me/delivery/delivered", driver.token(), "{\"code\":\"0000\"}").status());
        post("/api/shops/me/orders/" + orderId + "/ready", s.shop(), null);

        // Before pickup the customer cannot see the driver: the driver is at the shop.
        Res before = get("/api/orders/" + orderId + "/tracking", s.customer());
        assertEquals(200, before.status(), before.body());
        assertTrue(before.<Boolean>read("$.driverAssigned"));
        assertNull(before.read("$.driver"), "showing the driver now would show where the shop is");

        Res pickedUp = post("/api/drivers/me/delivery/picked-up", driver.token(), null);
        assertEquals(200, pickedUp.status(), pickedUp.body());
        assertEquals("PICKED_UP", pickedUp.read("$.status"));
        assertEquals("9 Door St", pickedUp.read("$.dropoff.addressLine"));
        assertEquals("Test Customer", pickedUp.read("$.dropoff.name"));
        assertEquals(s.lat() + 0.01, pickedUp.<Number>read("$.dropoff.latitude").doubleValue(), 1e-9);
        assertEquals("Gate code 4411", pickedUp.read("$.note"));
        assertFalse(pickedUp.body().contains(productName), "never the product names");
        assertFalse(pickedUp.body().contains("deliveryCode"), "the driver is never sent the code");
        assertEquals(409, post("/api/drivers/me/delivery/picked-up", driver.token(), null).status());
        assertEquals(409, post("/api/drivers/me/delivery/release", driver.token(), null).status(), "too late to give it back");

        // 4. On the road: the customer follows the driver's pings.
        post("/api/drivers/me/location", driver.token(), position(s.lat() + 0.008, s.lng() - 0.008));
        Res tracking = get("/api/orders/" + orderId + "/tracking", s.customer());
        assertEquals(s.lat() + 0.008, tracking.<Number>read("$.driver.latitude").doubleValue(), 1e-9);
        assertNotNull(tracking.read("$.driver.locatedAt"));
        assertTrue(tracking.<Integer>read("$.driver.etaMinutes") <= 3, "0.3 km from the door: " + tracking.body());

        // 5. At the door: the customer's code ends the delivery.
        String code = get("/api/orders/" + orderId, s.customer()).read("$.deliveryCode");
        String wrong = code.equals("0000") ? "1111" : "0000";
        assertEquals(400, post("/api/drivers/me/delivery/delivered", driver.token(), "{\"code\":\"abcd\"}").status());
        assertEquals(400, post("/api/drivers/me/delivery/delivered", driver.token(), "{\"code\":\"" + wrong + "\"}").status());
        assertEquals("PICKED_UP", get("/api/orders/" + orderId, s.customer()).read("$.status"));
        Res delivered = post("/api/drivers/me/delivery/delivered", driver.token(), "{\"code\":\"" + code + "\"}");
        assertEquals(200, delivered.status(), delivered.body());
        assertEquals("DELIVERED", delivered.read("$.status"));
        assertEquals("DELIVERED", get("/api/orders/" + orderId, s.customer()).read("$.status"));
        assertNull(get("/api/orders/" + orderId + "/tracking", s.customer()).read("$.driver"), "tracking stops at the door");

        // 6. Afterwards: no active delivery, it shows in history, and the driver is free for the next job.
        assertEquals(404, get("/api/drivers/me/delivery", driver.token()).status());
        Res history = get("/api/drivers/me/deliveries", driver.token());
        assertEquals(1, history.<Integer>read("$.page.totalElements"));
        int paid = history.<Integer>read("$.content[0].payCents");
        assertTrue(paid > 0, "engaged time plus km allowance");
        assertEquals(paid, get("/api/admin/orders/" + orderId, s.admin()).<Integer>read("$.driverPayCents"));
        assertEquals(0, history.<Integer>read("$.content[0].tipCents"));
        assertEquals(3, history.<Integer>read("$.content[0].units"));
        long next = acceptedOrder(s, s.customer(), 1);
        assertEquals(200, post("/api/drivers/me/jobs/" + next + "/accept", driver.token(), null).status());
    }

    @Test
    void unitsCountOnlyWhatTheShopCouldSupply() {
        Scene s = scene(10);
        long rowB = stock(s.shop(), product(s.admin(), "Other " + java.util.UUID.randomUUID()), 500, 10);
        Res placed = post("/api/orders", s.customer(), orderJsonWith(s.shopId(), java.util.UUID.randomUUID().toString(),
                doorNear(s), item(s.rowId(), 2), item(rowB, 4)));
        List<Integer> missing = placed.read("$.items[?(@.quantity == 4)].id");
        post("/api/shops/me/orders/" + placed.id() + "/accept", s.shop(), "{\"unavailableItemIds\":" + missing + "}");

        assertEquals(2, get("/api/drivers/me/jobs", driverNear(s).token()).<Integer>read("$[0].units"),
                "the 4 units the shop did not have are not in the bag");
    }

    @Test
    void driversSeeAndTouchOnlyTheirOwnWork() {
        Scene s = scene(10);
        long orderId = acceptedOrder(s, s.customer(), 2);
        DriverAt driver = driverNear(s);
        DriverAt other = driverNear(s);
        DriverAt farAway = driverAt(s.admin(), s.lat() + 1.5, s.lng());

        assertEquals(0, get("/api/drivers/me/jobs", farAway.token()).<List<?>>read("$").size(), "167 km away: not offered");
        assertEquals(404, post("/api/drivers/me/jobs/" + orderId + "/accept", farAway.token(), null).status(),
                "and guessing the order id does not help");

        assertEquals(200, post("/api/drivers/me/jobs/" + orderId + "/accept", driver.token(), null).status());
        assertEquals(409, post("/api/drivers/me/jobs/" + orderId + "/accept", other.token(), null).status(), "already taken");
        assertEquals(0, get("/api/drivers/me/jobs", other.token()).<List<?>>read("$").size(), "taken jobs leave the list");
        assertEquals(404, get("/api/drivers/me/delivery", other.token()).status());
        assertEquals(404, post("/api/drivers/me/delivery/picked-up", other.token(), null).status());
        assertEquals(404, post("/api/drivers/me/delivery/delivered", other.token(), "{\"code\":\"1234\"}").status());
        assertEquals(404, post("/api/drivers/me/delivery/release", other.token(), null).status());
        assertEquals(409, post("/api/drivers/me/jobs/999999999/accept", other.token(), null).status());

        // Tracking belongs to the order's own customer.
        assertEquals(404, get("/api/orders/" + orderId + "/tracking", customer()).status());
        assertEquals(403, get("/api/orders/" + orderId + "/tracking", driver.token()).status());
        assertEquals(403, get("/api/orders/" + orderId + "/tracking", s.shop()).status());

        // Each role is kept to its own routes.
        for (String path : List.of("/api/drivers/me", "/api/drivers/me/jobs", "/api/drivers/me/delivery", "/api/drivers/me/deliveries")) {
            assertEquals(401, get(path, null).status(), path);
            assertEquals(403, get(path, s.shop()).status(), path);
            assertEquals(403, get(path, s.customer()).status(), path);
            assertEquals(403, get(path, s.admin()).status(), path);
        }
        for (String path : List.of("/api/shops/me", "/api/shops/me/orders", "/api/orders", "/api/browse/products?" + HERE,
                "/api/admin/drivers", "/api/admin/shops")) {
            assertEquals(403, get(path, driver.token()).status(), "driver on " + path);
        }
        assertEquals(403, put("/api/admin/drivers/" + driver.userId() + "/status", driver.token(), "{\"status\":\"APPROVED\"}").status());

        // The shop never learns who the customer is or where they live, driver or no driver.
        Res shopView = get("/api/shops/me/orders/" + orderId, s.shop());
        for (String secret : List.of("9 Door St", "deliveryCode", "driverId", "Dana")) {
            assertFalse(shopView.body().contains(secret), "shop view leaks " + secret + ": " + shopView.body());
        }
    }

    @Test
    void wrongDeliveryCodesAreLimited() {
        Scene s = scene(10);
        long orderId = acceptedOrder(s, s.customer(), 1);
        DriverAt driver = driverNear(s);
        post("/api/drivers/me/jobs/" + orderId + "/accept", driver.token(), null);
        post("/api/shops/me/orders/" + orderId + "/ready", s.shop(), null);
        post("/api/drivers/me/delivery/picked-up", driver.token(), null);
        String code = get("/api/orders/" + orderId, s.customer()).read("$.deliveryCode");
        assertTrue(code.matches("\\d{4}"), code);
        String wrong = code.equals("0000") ? "1111" : "0000";

        for (int i = 0; i < 5; i++) {
            assertEquals(400, post("/api/drivers/me/delivery/delivered", driver.token(), "{\"code\":\"" + wrong + "\"}").status());
        }
        assertEquals(429, post("/api/drivers/me/delivery/delivered", driver.token(), "{\"code\":\"" + code + "\"}").status(),
                "after 5 tries even the right code is refused, so the code cannot be guessed");
        assertEquals("PICKED_UP", get("/api/orders/" + orderId, s.customer()).read("$.status"));

        JdbcClient db = JdbcClient.create(dataSource);
        assertEquals(5, db.sql("select delivery_code_attempts from orders where id = :id")
                .param("id", orderId).query(Integer.class).single(), "wrong codes are recorded even when the API rejects them");
        db.sql("update orders set delivery_code_window_expires_at = now() - interval '1 second' where id = :id")
                .param("id", orderId).update();
        assertEquals(200, post("/api/drivers/me/delivery/delivered", driver.token(), "{\"code\":\"" + code + "\"}").status());
        assertEquals(1, db.sql("select delivery_code_attempts from orders where id = :id")
                .param("id", orderId).query(Integer.class).single(), "expiry opens a fresh window");
    }

    @Test
    void aReleasedJobGoesBackToThePool() {
        Scene s = scene(10);
        long orderId = acceptedOrder(s, s.customer(), 1);
        DriverAt first = driverNear(s);
        DriverAt second = driverNear(s);

        post("/api/drivers/me/jobs/" + orderId + "/accept", first.token(), null);
        assertEquals(204, post("/api/drivers/me/delivery/release", first.token(), null).status());
        assertEquals(404, get("/api/drivers/me/delivery", first.token()).status());
        assertFalse(get("/api/orders/" + orderId, s.customer()).<Boolean>read("$.driverAssigned"));

        assertEquals(List.of((int) orderId), get("/api/drivers/me/jobs", second.token()).<List<Integer>>read("$[*].orderId"));
        assertEquals(200, post("/api/drivers/me/jobs/" + orderId + "/accept", second.token(), null).status());
        assertEquals(409, post("/api/drivers/me/jobs/" + orderId + "/accept", first.token(), null).status());
    }

    @Test
    void jobsAreOfferedOldestFirstAndOnlyWhenTheShopHasAccepted() {
        Scene s = scene(20);
        Res placed = post("/api/orders", s.customer(), orderJsonWith(s.shopId(), java.util.UUID.randomUUID().toString(),
                doorNear(s), item(s.rowId(), 1)));
        DriverAt driver = driverNear(s);
        assertEquals(0, get("/api/drivers/me/jobs", driver.token()).<List<?>>read("$").size(), "PLACED: the shop has not accepted");
        assertEquals(409, post("/api/drivers/me/jobs/" + placed.id() + "/accept", driver.token(), null).status());

        post("/api/shops/me/orders/" + placed.id() + "/accept", s.shop(), null);
        long second = acceptedOrder(s, customer(), 1);
        long third = acceptedOrder(s, customer(), 1);
        assertEquals(List.of((int) placed.id(), (int) second, (int) third),
                get("/api/drivers/me/jobs", driver.token()).<List<Integer>>read("$[*].orderId"), "first in, first out");

        // A driver who has gone offline is not offered work.
        post("/api/drivers/me/offline", driver.token(), null);
        assertEquals(409, get("/api/drivers/me/jobs", driver.token()).status());
    }
}
