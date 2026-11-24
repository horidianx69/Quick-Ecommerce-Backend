package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Order fees, the price quote, and tips. */
class FeesTest extends ApiTestBase {

    @Test
    void deliveryFeeFollowsTheTiersAndHandlingIsAlwaysCharged() {
        String admin = adminToken();
        Stocked s = stockedShop(admin, 1000, 100); // 14.99 each
        String customer = customer();

        Res small = order(customer, s.shopId(), item(s.rowId(), 1)); // 14.99
        assertEquals(599, small.<Integer>read("$.deliveryFeeCents"));
        assertEquals(299, small.<Integer>read("$.smallCartFeeCents"), "under $15");
        assertEquals(199, small.<Integer>read("$.handlingFeeCents"));

        Res mid = order(customer, s.shopId(), item(s.rowId(), 4)); // 59.96
        assertEquals(499, mid.<Integer>read("$.deliveryFeeCents"), "$50 to $99.99");
        assertEquals(0, mid.<Integer>read("$.smallCartFeeCents"));

        Res big = order(customer, s.shopId(), item(s.rowId(), 7)); // 104.93
        assertEquals(0, big.<Integer>read("$.deliveryFeeCents"), "$100 and up: free delivery");
        assertEquals(199, big.<Integer>read("$.handlingFeeCents"), "handling is always charged");
        assertEquals(10493 + 199, big.<Integer>read("$.totalCents"));
    }

    @Test
    void theQuoteShowsEveryFeeBeforeOrderingAndMatchesTheOrder() {
        String admin = adminToken();
        Stocked s = stockedShop(admin, 1000, 3);
        String customer = customer();
        String body = orderJsonWith(s.shopId(), UUID.randomUUID().toString(), NEAR_ADDRESS, item(s.rowId(), 2));

        Res quote = post("/api/orders/quote", customer, body.replace("\"idempotencyKey\"", "\"tipCents\":200,\"ignored\""));
        assertEquals(200, quote.status(), quote.body());
        assertEquals(2998, quote.<Integer>read("$.subtotalCents"));
        assertEquals(599, quote.<Integer>read("$.deliveryFeeCents"));
        assertEquals(199, quote.<Integer>read("$.handlingFeeCents"));
        assertEquals(0, quote.<Integer>read("$.smallCartFeeCents"));
        assertEquals(200, quote.<Integer>read("$.tipCents"));
        assertEquals(2998 + 599 + 199 + 200, quote.<Integer>read("$.totalCents"));
        assertEquals(10000, quote.<Integer>read("$.freeDeliveryFromCents"), "so the app can say how much more for free delivery");
        assertEquals(List.of(100, 200, 500, 1000), quote.<List<Integer>>read("$.tipOptionsCents"));
        assertEquals(List.of(true), quote.<List<Boolean>>read("$.items[*].inStock"));
        assertEquals(3, stockOf(s.shop(), s.rowId()), "a quote takes no stock");

        Res placed = post("/api/orders", customer, body.replace("\"items\"", "\"tipCents\":200,\"items\""));
        assertEquals(201, placed.status(), placed.body());
        assertEquals(quote.<Integer>read("$.totalCents"), placed.<Integer>read("$.totalCents"), "the quote is what is charged");

        Res tooMany = post("/api/orders/quote", customer,
                orderJsonWith(s.shopId(), "x", NEAR_ADDRESS, item(s.rowId(), 5)).replace("\"idempotencyKey\":\"x\",", ""));
        assertEquals(List.of(false), tooMany.<List<Boolean>>read("$.items[*].inStock"), "only 1 left");
        Res other = post("/api/orders/quote", customer,
                orderJsonWith(s.shopId(), "x", NEAR_ADDRESS, item(999999999, 1)).replace("\"idempotencyKey\":\"x\",", ""));
        assertEquals(400, other.status());
        assertEquals(999999999, other.<Integer>read("$.shopProductId"));
        assertEquals(403, post("/api/orders/quote", s.shop(), body).status());
    }

    @Test
    void lateNightFeeAppliesInsideTheWindowInTheShopsTime() {
        String admin = adminToken();
        Stocked s = stockedShop(admin, 1000, 10); // a BC shop: America/Vancouver
        LocalTime now = time.localNow("America/Vancouver").toLocalTime().withNano(0);
        try {
            put("/api/admin/settings", admin, "{\"lateNightStart\":\"%s\",\"lateNightEnd\":\"%s\"}"
                    .formatted(now.minusHours(1), now.plusHours(1)));
            assertEquals(199, order(customer(), s.shopId(), item(s.rowId(), 2)).<Integer>read("$.lateNightFeeCents"));

            put("/api/admin/settings", admin, "{\"lateNightStart\":\"%s\",\"lateNightEnd\":\"%s\"}"
                    .formatted(now.plusHours(2), now.plusHours(3)));
            assertEquals(0, order(customer(), s.shopId(), item(s.rowId(), 2)).<Integer>read("$.lateNightFeeCents"));
        } finally {
            put("/api/admin/settings", admin, STEADY_FEES);
        }
    }

    @Test
    void surgeAppliesWhenWaitingOrdersOutnumberNearbyDrivers() {
        Scene s = scene(100); // an area of its own
        try {
            put("/api/admin/settings", s.admin(), "{\"surgeMode\":\"AUTO\"}");
            assertEquals(199, placeAt(s).<Integer>read("$.surgeFeeCents"), "no driver online nearby at all");

            parallel(3, i -> driverNear(s));
            assertEquals(0, placeAt(s).<Integer>read("$.surgeFeeCents"), "1 order for 3 drivers: no surge");

            for (int i = 0; i < 5; i++) {
                acceptedOrder(s, customer(), 1); // 5 orders waiting for a driver
            }
            assertEquals(199, placeAt(s).<Integer>read("$.surgeFeeCents"), "6 orders for 3 drivers: at least 2 to 1");

            put("/api/admin/settings", s.admin(), "{\"surgeMode\":\"OFF\"}");
            assertEquals(0, placeAt(s).<Integer>read("$.surgeFeeCents"), "admin switched it off");
            put("/api/admin/settings", s.admin(), "{\"surgeMode\":\"ON\"}");
            assertEquals(199, placeAt(s).<Integer>read("$.surgeFeeCents"), "admin switched it on");
        } finally {
            put("/api/admin/settings", s.admin(), STEADY_FEES);
        }
    }

    Res placeAt(Scene s) {
        Res res = post("/api/orders", customer(), orderJsonWith(s.shopId(), UUID.randomUUID().toString(), doorNear(s), item(s.rowId(), 1)));
        assertEquals(201, res.status(), res.body());
        return res;
    }

    @Test
    void tipsAreAddedOnceAndTheDriverSeesThemOnlyAfterDelivery() {
        Scene s = scene(20);
        String customer = s.customer();
        String body = orderJsonWith(s.shopId(), UUID.randomUUID().toString(), doorNear(s), item(s.rowId(), 1));
        assertEquals(400, post("/api/orders", customer, body.replace("\"items\"", "\"tipCents\":300,\"items\"")).status(),
                "only the offered amounts");

        Res tipped = post("/api/orders", customer, body.replace("\"items\"", "\"tipCents\":500,\"items\""));
        assertEquals(201, tipped.status(), tipped.body());
        assertEquals(500, tipped.<Integer>read("$.tipCents"));
        long id = tipped.id();
        post("/api/shops/me/orders/" + id + "/accept", s.shop(), null);
        DriverAt driver = driverNear(s);

        Res job = get("/api/drivers/me/jobs", driver.token());
        assertFalse(job.body().contains("tip"), "no tip on the job list: " + job.body());
        Res accepted = post("/api/drivers/me/jobs/" + id + "/accept", driver.token(), null);
        assertNull(accepted.read("$.tipCents"), "the driver does not see the tip before delivering");
        post("/api/shops/me/orders/" + id + "/ready", s.shop(), null);
        assertNull(post("/api/drivers/me/delivery/picked-up", driver.token(), null).read("$.tipCents"));
        String code = get("/api/orders/" + id, customer).read("$.deliveryCode");
        Res delivered = post("/api/drivers/me/delivery/delivered", driver.token(), "{\"code\":\"" + code + "\"}");
        assertEquals(500, delivered.<Integer>read("$.tipCents"), "now they see it");
        assertEquals(500, get("/api/drivers/me/deliveries", driver.token()).<Integer>read("$.content[0].tipCents"));

        assertEquals(409, post("/api/orders/" + id + "/tip", customer, "{\"tipCents\":200}").status(), "already tipped");

        // Tipping after delivery, when none was given at checkout. Twice at once: only one lands.
        long second = acceptedOrder(s, customer, 1);
        DriverAt other = driverNear(s);
        post("/api/drivers/me/jobs/" + second + "/accept", other.token(), null);
        post("/api/shops/me/orders/" + second + "/ready", s.shop(), null);
        post("/api/drivers/me/delivery/picked-up", other.token(), null);
        post("/api/drivers/me/delivery/delivered", other.token(),
                "{\"code\":\"" + get("/api/orders/" + second, customer).read("$.deliveryCode") + "\"}");
        assertEquals(400, post("/api/orders/" + second + "/tip", customer, "{\"tipCents\":300}").status());
        List<Res> tips = parallel(6, i -> post("/api/orders/" + second + "/tip", customer, "{\"tipCents\":" + (i % 2 == 0 ? 100 : 1000) + "}"));
        assertEquals(1, count(tips, 200), ConcurrencyTest.statuses(tips));
        assertEquals(5, count(tips, 409));
        int tip = get("/api/orders/" + second, customer).read("$.tipCents");
        assertTrue(tip == 100 || tip == 1000);
        assertEquals(404, post("/api/orders/" + second + "/tip", customer(), "{\"tipCents\":100}").status(), "not their order");
    }
}
