package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quickcommerce.order.OrderService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Orders under race conditions. Each test names the one outcome that must hold however the threads interleave. */
class OrderConcurrencyTest extends ApiTestBase {

    @Autowired
    OrderService orderService;

    @Test
    void fortyCustomersRaceForTenItems() {
        Stocked s = stockedShop(adminToken(), 1000, 10);
        List<String> customers = parallel(40, i -> customer());

        List<Res> results = parallel(40, i -> post("/api/orders", customers.get(i), orderJson(s.shopId(), item(s.rowId(), 1))));

        assertEquals(10, count(results, 201), "exactly 10 orders for 10 items: " + ConcurrencyTest.statuses(results));
        assertEquals(30, count(results, 409), "the other 30 are told there is not enough stock");
        assertEquals(0, stockOf(s.shop(), s.rowId()));
        assertEquals(10, get("/api/shops/me/orders?status=PLACED", s.shop()).<Integer>read("$.page.totalElements"));
    }

    @Test
    void ordersForTheSameProductsInOppositeOrderDoNotDeadlock() {
        String admin = adminToken();
        Stocked s = stockedShop(admin, 1000, 100);
        long rowB = addStock(s.shop(), admin, 500, 100);
        List<String> customers = parallel(30, i -> customer());

        List<Res> results = parallel(30, i -> post("/api/orders", customers.get(i), i % 2 == 0
                ? orderJson(s.shopId(), item(s.rowId(), 1), item(rowB, 1))
                : orderJson(s.shopId(), item(rowB, 1), item(s.rowId(), 1))));

        assertEquals(30, count(results, 201), ConcurrencyTest.statuses(results));
        assertEquals(70, stockOf(s.shop(), s.rowId()));
        assertEquals(70, stockOf(s.shop(), rowB));
    }

    @Test
    void theSameCheckoutSentTenTimesAtOnceMakesOneOrder() {
        Stocked s = stockedShop(adminToken(), 1000, 10);
        String customer = customer();
        String body = orderJsonWith(s.shopId(), UUID.randomUUID().toString(), NEAR_ADDRESS, item(s.rowId(), 3));

        List<Res> results = parallel(10, i -> post("/api/orders", customer, body));

        assertEquals(1, count(results, 201), ConcurrencyTest.statuses(results));
        assertEquals(9, count(results, 200) + count(results, 409), "the rest are replays or told to retry");
        assertEquals(7, stockOf(s.shop(), s.rowId()), "stock is taken once");
        assertEquals(1, get("/api/orders", customer).<Integer>read("$.page.totalElements"));
        assertEquals(200, post("/api/orders", customer, body).status(), "a later retry gets the order back");
    }

    @Test
    void whenShopAcceptsWhileCustomerCancelsExactlyOneWins() {
        Stocked s = stockedShop(adminToken(), 1000, 100);
        String customer = customer();
        int accepted = 0;

        for (int round = 0; round < 15; round++) {
            long id = order(customer, s.shopId(), item(s.rowId(), 2)).id();
            List<Res> results = parallel(2, i -> i == 0
                    ? post("/api/shops/me/orders/" + id + "/accept", s.shop(), null)
                    : post("/api/orders/" + id + "/cancel", customer, null));

            assertEquals(1, count(results, 200), "round " + round + ": " + ConcurrencyTest.statuses(results));
            assertEquals(1, count(results, 409));
            String status = get("/api/orders/" + id, customer).read("$.status");
            assertEquals(results.get(0).status() == 200 ? "ACCEPTED" : "CANCELLED", status);
            if (status.equals("ACCEPTED")) {
                accepted++;
            }
        }
        assertEquals(100 - 2 * accepted, stockOf(s.shop(), s.rowId()),
                "stock is held for the " + accepted + " accepted orders and returned for the cancelled ones");
    }

    @Test
    void tenRejectsAtOnceReturnTheStockOnce() {
        Stocked s = stockedShop(adminToken(), 1000, 10);
        long id = order(customer(), s.shopId(), item(s.rowId(), 4)).id();

        List<Res> results = parallel(10, i -> post("/api/shops/me/orders/" + id + "/reject", s.shop(), null));

        assertEquals(1, count(results, 200), ConcurrencyTest.statuses(results));
        assertEquals(9, count(results, 409));
        assertEquals(10, stockOf(s.shop(), s.rowId()), "4 units came back once, not ten times");
    }

    @Test
    void whenTheOrderExpiresWhileTheShopAcceptsExactlyOneWins() {
        Stocked s = stockedShop(adminToken(), 1000, 100);
        String customer = customer();
        int accepted = 0;

        for (int round = 0; round < 10; round++) {
            long id = order(customer, s.shopId(), item(s.rowId(), 2)).id();
            List<Integer> results = parallel(2, i -> i == 0
                    ? post("/api/shops/me/orders/" + id + "/accept", s.shop(), null).status()
                    : orderService.expirePlacedBefore(Instant.now().plusSeconds(5)));

            String status = get("/api/orders/" + id, customer).read("$.status");
            assertTrue(status.equals("ACCEPTED") || status.equals("EXPIRED"), status);
            assertEquals(status.equals("ACCEPTED") ? 200 : 409, results.get(0), "round " + round);
            if (status.equals("ACCEPTED")) {
                accepted++;
            }
        }
        assertEquals(100 - 2 * accepted, stockOf(s.shop(), s.rowId()));
    }
}
