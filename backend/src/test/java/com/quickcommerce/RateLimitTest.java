package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Runs with small limits (the other tests lift them) and with the API docs off, as in production. */
@TestPropertySource(properties = {
        "app.rate-limit.per-user-per-minute=40",
        "app.orders.checkouts-per-minute=2",
        "app.orders.quotes-per-minute=3",
        "spring.datasource.hikari.maximum-pool-size=2",
        "app.orders.max-concurrent-checkouts=20",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"})
class RateLimitTest extends ApiTestBase {

    @Autowired
    EmbeddedPostgres postgres;

    /** No shared setup: its admin calls would count toward the limits checked here. */
    @Override
    void steadyFees() {}

    @Test
    void oneAccountCannotFloodTheApi() {
        String customer = customer();
        List<Res> results = parallel(45, i -> get("/api/orders", customer));
        assertEquals(40, count(results, 200), ConcurrencyTest.statuses(results));
        assertEquals(5, count(results, 429));
        Res refused = get("/api/orders", customer);
        assertEquals("60", refused.headers().firstValue("Retry-After").orElse(null));
        assertTrue(refused.body().contains("Too many requests"), refused.body());
        assertEquals(200, get("/api/orders", customer()).status(), "another account is not affected");
    }

    @Test
    void checkoutsAndQuotesHaveTheirOwnSmallerLimit() {
        String admin = adminToken();
        Stocked s = stockedShop(admin, 1000, 10);
        String customer = customer();
        String quote = orderJsonWith(s.shopId(), "x", NEAR_ADDRESS, item(s.rowId(), 1)).replace("\"idempotencyKey\":\"x\",", "");
        for (int i = 0; i < 3; i++) {
            assertEquals(200, post("/api/orders/quote", customer, quote).status());
        }
        assertEquals(429, post("/api/orders/quote", customer, quote).status());

        assertEquals(201, post("/api/orders", customer, orderJson(s.shopId(), item(s.rowId(), 1))).status());
        assertEquals(201, post("/api/orders", customer, orderJson(s.shopId(), item(s.rowId(), 1))).status());
        Res third = post("/api/orders", customer, orderJson(s.shopId(), item(s.rowId(), 1)));
        assertEquals(429, third.status(), third.body());
        assertEquals(8, stockOf(s.shop(), s.rowId()), "the refused checkout took no stock");
    }

    @Test
    void aBlockedCheckoutLeavesRoomForOtherRequests() throws Exception {
        Stocked s = stockedShop(adminToken(), 1000, 10);
        String customer = customer();
        String body = orderJsonWith(s.shopId(), "blocked-checkout", NEAR_ADDRESS, item(s.rowId(), 1));
        String quote = body.replace("\"idempotencyKey\":\"blocked-checkout\",", "");

        try (var executor = Executors.newSingleThreadExecutor(); Connection lock = postgres.getPostgresDatabase().getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("select id from shop_products where id = ? for update")) {
                statement.setLong(1, s.rowId());
                statement.executeQuery().close();
            }
            var checkout = executor.submit(() -> post("/api/orders", customer, body));
            try {
                assertTrue(waitingForStockLock(), "the checkout holds its slot while it waits for stock");
                assertEquals(503, post("/api/orders/quote", customer, quote).status());
                assertEquals(200, get("/api/orders", customer).status(), "a connection remains available for other work");
            } finally {
                lock.rollback();
            }
            assertEquals(201, checkout.get(5, TimeUnit.SECONDS).status());
            assertEquals(200, post("/api/orders/quote", customer, quote).status(), "the slot is returned after the checkout");
        }
    }

    private boolean waitingForStockLock() throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        try (var monitor = postgres.getPostgresDatabase().getConnection(); var statement = monitor.createStatement()) {
            while (System.nanoTime() < deadline) {
                try (var rows = statement.executeQuery("""
                        select exists (select 1 from pg_stat_activity where wait_event_type = 'Lock'
                            and query like '%update shop_products sp set quantity%')
                        """)) {
                    if (rows.next() && rows.getBoolean(1)) {
                        return true;
                    }
                }
                Thread.sleep(10);
            }
        }
        return false;
    }

    @Test
    void apiDocsAreOffUnlessSwitchedOn() {
        assertEquals(404, get("/v3/api-docs", null).status(), "the contract is not served");
    }
}
