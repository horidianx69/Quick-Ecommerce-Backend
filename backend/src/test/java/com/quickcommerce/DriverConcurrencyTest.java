package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The driver side under race conditions. */
class DriverConcurrencyTest extends ApiTestBase {

    @Test
    void twentyDriversAcceptOneJobAndOneWins() {
        Scene s = scene(10);
        long orderId = acceptedOrder(s, s.customer(), 1);
        List<DriverAt> drivers = parallel(20, i -> driverNear(s));

        List<Res> results = parallel(20, i -> post("/api/drivers/me/jobs/" + orderId + "/accept", drivers.get(i).token(), null));

        assertEquals(1, count(results, 200), ConcurrencyTest.statuses(results));
        assertEquals(19, count(results, 409), "the rest are told it was taken");
        long withDelivery = drivers.stream().filter(d -> get("/api/drivers/me/delivery", d.token()).status() == 200).count();
        assertEquals(1, withDelivery, "exactly one driver holds the order");
    }

    @Test
    void oneDriverAcceptingFiveJobsAtOnceGetsOne() {
        Scene s = scene(10);
        List<Long> jobs = List.of(acceptedOrder(s, customer(), 1), acceptedOrder(s, customer(), 1),
                acceptedOrder(s, customer(), 1), acceptedOrder(s, customer(), 1), acceptedOrder(s, customer(), 1));
        DriverAt driver = driverNear(s);

        List<Res> results = parallel(5, i -> post("/api/drivers/me/jobs/" + jobs.get(i) + "/accept", driver.token(), null));

        assertEquals(1, count(results, 200), "a driver carries one order at a time: " + ConcurrencyTest.statuses(results));
        assertEquals(4, count(results, 409));
        DriverAt second = driverNear(s);
        assertEquals(4, get("/api/drivers/me/jobs", second.token()).<List<?>>read("$").size(), "the other 4 are still open");
    }

    @Test
    void pickupAndDeliveryEachHappenOnce() {
        Scene s = scene(10);
        long orderId = acceptedOrder(s, s.customer(), 1);
        DriverAt driver = driverNear(s);
        post("/api/drivers/me/jobs/" + orderId + "/accept", driver.token(), null);
        post("/api/shops/me/orders/" + orderId + "/ready", s.shop(), null);

        List<Res> pickups = parallel(8, i -> post("/api/drivers/me/delivery/picked-up", driver.token(), null));
        assertEquals(1, count(pickups, 200), ConcurrencyTest.statuses(pickups));
        assertEquals(7, count(pickups, 409));

        String code = get("/api/orders/" + orderId, s.customer()).read("$.deliveryCode");
        // 4 at once: under the 5-try limit, so every one reaches the code check.
        List<Res> deliveries = parallel(4, i -> post("/api/drivers/me/delivery/delivered", driver.token(), "{\"code\":\"" + code + "\"}"));
        assertEquals(1, count(deliveries, 200), ConcurrencyTest.statuses(deliveries));
        assertEquals(1, get("/api/drivers/me/deliveries", driver.token()).<Integer>read("$.page.totalElements"));
        assertEquals("DELIVERED", get("/api/orders/" + orderId, s.customer()).read("$.status"));
    }

    @Test
    void parallelWrongCodesCannotBeatTheLimit() {
        Scene s = scene(10);
        long orderId = acceptedOrder(s, s.customer(), 1);
        DriverAt driver = driverNear(s);
        post("/api/drivers/me/jobs/" + orderId + "/accept", driver.token(), null);
        post("/api/shops/me/orders/" + orderId + "/ready", s.shop(), null);
        post("/api/drivers/me/delivery/picked-up", driver.token(), null);
        String code = get("/api/orders/" + orderId, s.customer()).read("$.deliveryCode");

        // 40 guesses at the same moment, none of them the real code.
        List<Res> guesses = parallel(40, i -> {
            String guess = "%04d".formatted(i);
            return post("/api/drivers/me/delivery/delivered", driver.token(),
                    "{\"code\":\"" + (guess.equals(code) ? "9999" : guess) + "\"}");
        });
        assertTrue(count(guesses, 400) <= 5, "only 5 guesses may be checked, but " + count(guesses, 400) + " were");
        assertEquals(40, count(guesses, 400) + count(guesses, 429));
        assertEquals(0, count(guesses, 200));
    }

    @Test
    void locationPingsCannotUndoASuspension() {
        Scene s = scene(1);
        DriverAt driver = driverNear(s);

        // The driver's app pings on 12 threads while admin suspends the driver.
        List<Res> results = parallel(13, i -> i == 0
                ? put("/api/admin/drivers/" + driver.userId() + "/status", s.admin(), "{\"status\":\"SUSPENDED\"}")
                : post("/api/drivers/me/location", driver.token(), position(s.lat() + i * 0.0001, s.lng())));

        assertEquals(200, results.get(0).status(), results.get(0).body());
        results.subList(1, 13).forEach(r -> assertTrue(r.status() == 204 || r.status() == 409, "ping -> " + r.status()));
        Res after = get("/api/drivers/me", driver.token());
        assertEquals("SUSPENDED", after.read("$.status"));
        assertFalse(after.<Boolean>read("$.online"), "a ping that raced the suspension did not bring the driver back online");
        assertEquals(409, post("/api/drivers/me/location", driver.token(), position(s.lat(), s.lng())).status());
    }

    @Test
    void manyDriversPingAndPollWithoutErrors() {
        Scene s = scene(10);
        acceptedOrder(s, s.customer(), 1);
        List<DriverAt> drivers = parallel(20, i -> driverNear(s));

        // 20 drivers, 15 rounds each of "ping, then poll for jobs": 600 requests at once.
        List<Integer> worst = parallel(20, i -> {
            int max = 0;
            for (int round = 0; round < 15; round++) {
                max = Math.max(max, post("/api/drivers/me/location", drivers.get(i).token(),
                        position(s.lat() + 0.004 + round * 0.00001, s.lng() - 0.004)).status());
                max = Math.max(max, get("/api/drivers/me/jobs", drivers.get(i).token()).status());
            }
            return max;
        });
        worst.forEach(status -> assertTrue(status == 200 || status == 204, "worst status " + status));
    }
}
