package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Admin actions racing other people on the same order or shop. */
class AdminConcurrencyTest extends ApiTestBase {

    @Test
    void adminCancelRacingTheShopsAcceptLeavesStockRight() {
        Scene s = scene(200);
        for (int round = 0; round < 15; round++) {
            long id = post("/api/orders", s.customer(), orderJsonWith(s.shopId(), UUID.randomUUID().toString(), doorNear(s),
                    item(s.rowId(), 2))).id();
            int before = stockOf(s.shop(), s.rowId());
            List<Res> results = parallel(2, i -> i == 0
                    ? post("/api/shops/me/orders/" + id + "/accept", s.shop(), null)
                    : post("/api/admin/orders/" + id + "/cancel", s.admin(), "{\"reason\":\"race\"}"));

            String status = get("/api/admin/orders/" + id, s.admin()).read("$.status");
            Res cancel = results.get(1);
            assertTrue(cancel.status() == 200 || cancel.status() == 409, "cancel -> " + cancel.status() + " " + cancel.body());
            assertEquals(cancel.status() == 200 ? "CANCELLED" : "ACCEPTED", status, "round " + round);
            assertEquals(status.equals("CANCELLED") ? before + 2 : before, stockOf(s.shop(), s.rowId()),
                    "round " + round + ": stock matches the final status");
        }
    }

    @Test
    void adminAssignRacingADriversAcceptLeavesExactlyOneDriver() {
        Scene s = scene(100);
        for (int round = 0; round < 10; round++) {
            long id = acceptedOrder(s, customer(), 1);
            DriverAt picked = driverNear(s);
            DriverAt racer = driverNear(s);
            List<Res> results = parallel(2, i -> i == 0
                    ? post("/api/admin/orders/" + id + "/assign", s.admin(), "{\"driverId\":" + picked.userId() + "}")
                    : post("/api/drivers/me/jobs/" + id + "/accept", racer.token(), null));

            assertEquals(200, results.get(0).status(), "admin's assignment always lands: " + results.get(0).body());
            long holders = List.of(picked, racer).stream()
                    .filter(d -> get("/api/drivers/me/delivery", d.token()).status() == 200).count();
            assertEquals(1, holders, "round " + round + ": exactly one driver holds the order");
            Number driverId = get("/api/admin/orders/" + id, s.admin()).read("$.driver.id");
            assertEquals(picked.userId(), driverId.longValue(), "admin's choice wins, whoever came first");
        }
    }

    @Test
    void oneDriverAssignedToFiveOrdersAtOnceGetsOne() {
        Scene s = scene(100);
        List<Long> orders = List.of(acceptedOrder(s, customer(), 1), acceptedOrder(s, customer(), 1),
                acceptedOrder(s, customer(), 1), acceptedOrder(s, customer(), 1), acceptedOrder(s, customer(), 1));
        DriverAt driver = driverNear(s);

        List<Res> results = parallel(5, i -> post("/api/admin/orders/" + orders.get(i) + "/assign", s.admin(),
                "{\"driverId\":" + driver.userId() + "}"));

        assertEquals(1, count(results, 200), "a driver carries one order at a time: " + ConcurrencyTest.statuses(results));
        assertEquals(4, count(results, 409));
    }

    @Test
    void theOwnerSavingWhileAdminSetsHoursKeepsAdminsHours() {
        String admin = adminToken();
        String owner = shopOwner();
        long shopId = get("/api/shops/me", owner).id();

        List<Res> results = parallel(9, i -> i == 0
                ? put("/api/admin/shops/" + shopId + "/hours", admin, ALL_WEEK)
                : put("/api/shops/me", owner, VALID_SHOP.replace("Corner Stop", "Corner Stop " + i)));

        assertEquals(9, count(results, 200), ConcurrencyTest.statuses(results));
        assertEquals(7, get("/api/admin/shops/" + shopId, admin).<List<?>>read("$.hours").size(),
                "no profile save wiped or duplicated the hours");
    }

    @Test
    void settingsChangesDuringOrdersNeverMixPrices() {
        Scene s = scene(500);
        List<String> customers = parallel(20, i -> customer());
        try {
            // 20 orders while admin flips the default markup between 150 and 200 ten times.
            List<Res> results = parallel(30, i -> i < 20
                    ? post("/api/orders", customers.get(i), orderJsonWith(s.shopId(), UUID.randomUUID().toString(), doorNear(s),
                            item(s.rowId(), 1)))
                    : put("/api/admin/settings", s.admin(), "{\"defaultMarkupPercent\":" + (i % 2 == 0 ? 200 : 150) + "}"));
            for (Res placed : results.subList(0, 20)) {
                assertEquals(201, placed.status(), placed.body());
                int price = placed.<Integer>read("$.items[0].unitPriceCents");
                assertTrue(price == 1499 || price == 1999, "each order uses one whole setting, got " + price);
                int smallCart = price < 1500 ? 299 : 0;
                assertEquals(price + 599 + 199 + smallCart, placed.<Integer>read("$.totalCents"));
            }
        } finally {
            put("/api/admin/settings", s.admin(), "{\"defaultMarkupPercent\":150}");
        }
    }
}
