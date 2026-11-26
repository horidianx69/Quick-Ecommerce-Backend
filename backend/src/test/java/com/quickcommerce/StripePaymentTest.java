package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.quickcommerce.order.OrderService;
import com.stripe.StripeClient;
import com.stripe.net.Webhook;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "app.payments.enabled=true",
        "app.payments.api-key=sk_test_local_fixture",
        "app.payments.webhook-secret=whsec_quike_local_fixture",
        "app.payments.return-url=http://localhost:5173/payments/return",
        "app.payments.poll-ms=75",
        "app.orders.max-concurrent-checkouts=5"
})
@Import(StripePaymentTest.StripeHttp.class)
class StripePaymentTest extends ApiTestBase {

    static final String SIGNING_SECRET = "whsec_quike_local_fixture";
    static final String WEBHOOK = "/api/payments/stripe/webhook";

    @TestConfiguration
    static class StripeHttp {
        @Bean
        StripeFixture fakeStripe() throws IOException {
            return new StripeFixture();
        }

        @Bean
        @Primary
        StripeClient localStripeClient(StripeFixture stripe) {
            return StripeClient.builder().setApiKey("sk_test_local_fixture").setApiBase(stripe.base())
                    .setConnectTimeout(1_000).setReadTimeout(3_000).setMaxNetworkRetries(0).build();
        }
    }

    @Autowired
    StripeFixture stripe;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OrderService orders;

    @Test
    void aCheckoutIsPrivateAndWebhookSignalsCannotAuthorizeAnUnpaidOrder() throws Exception {
        Stocked stocked = stockedShop(adminToken(), 900, 5);
        String customer = customer();
        long id = order(customer, stocked.shopId(), item(stocked.rowId(), 1)).id();
        StripeFixture.Checkout checkout = opened(id, customer);
        assertEquals("PAYMENT_PENDING", status(id, customer));
        assertEquals(401, get(paymentPath(id), null).status());
        assertEquals(404, get(paymentPath(id), customer()).status());
        assertEquals(403, get(paymentPath(id), stocked.shop()).status());
        assertEquals(403, get("/api/admin/orders/" + id + "/payment", customer).status());
        assertEquals(404, get("/api/shops/me/orders/" + id, stocked.shop()).status());

        String payload = event(checkout.session, false);
        assertEquals(400, post(WEBHOOK, null, payload).status());
        assertEquals(400, webhook(payload, signed(payload, Instant.now().minusSeconds(301)), false).status());
        assertEquals(400, webhook(payload, signed(payload, Instant.now().plusSeconds(301)), false).status());
        String validSignature = signed(payload, Instant.now());
        assertEquals(400, webhook(payload + " ", validSignature, false).status());
        assertEquals(400, webhook(payload, validSignature + ",t=0", false).status());
        assertEquals(400, webhook(event(checkout.session, true)).status());
        assertEquals(400, webhook("").status());
        assertEquals(400, webhook("null").status());
        assertEquals(413, webhook(" ".repeat(256 * 1024 + 1), validSignature, false).status());
        assertEquals(411, webhook(" ".repeat(256 * 1024 + 1), validSignature, true).status());
        assertEquals(204, webhook(payload).status());
        assertEquals("PAYMENT_PENDING", status(id, customer));
        assertEquals(0, checkout.captures.get());

        checkout.authorize();
        List<Res> duplicates = parallel(24, n -> webhookUnchecked(payload));
        assertEquals(24, count(duplicates, 204));
        await(() -> status(id, customer), "PLACED");
        assertEquals("AUTHORIZED", get(paymentPath(id), customer).read("$.state"));
        assertNull(get(paymentPath(id), customer).read("$.checkoutUrl"));
        assertEquals(0, checkout.captures.get());
    }

    @Test
    void parallelShopAcceptsCaptureOnlyAvailableItemsAndRecoverLostResponses() throws Exception {
        Stocked stocked = stockedShop(adminToken(), 1_200, 8);
        long other = addStock(stocked.shop(), adminToken(), 600, 8);
        String customer = customer();
        var placed = order(customer, stocked.shopId(), item(stocked.rowId(), 2), item(other, 1));
        long id = placed.id();
        StripeFixture.Checkout checkout = authorize(id, customer);
        Number missing = placed.read("$.items[1].id");
        checkout.loseCaptureResponse = true;
        List<Res> results = parallel(18, n -> post("/api/shops/me/orders/" + id + "/accept", stocked.shop(),
                "{\"unavailableItemIds\":[" + missing + "]}"));
        assertEquals(1, count(results, 200));
        assertEquals(17, count(results, 409));
        await(() -> status(id, customer), "ACCEPTED");
        Res accepted = get("/api/orders/" + id, customer);
        assertEquals(List.of(missing.intValue()), accepted.<List<Integer>>read("$.items[?(@.unavailable == true)].id"));
        long remaining = 2L * placed.<Number>read("$.items[0].unitPriceCents").longValue();
        long feesAndTip = placed.<Number>read("$.totalCents").longValue() - placed.<Number>read("$.subtotalCents").longValue();
        assertEquals(remaining + feesAndTip, checkout.received);
        assertEquals(remaining + feesAndTip, accepted.<Number>read("$.totalCents").longValue());
        assertEquals(1, checkout.captures.get());

        checkout.loseRefundResponse = true;
        String admin = adminToken();
        List<Res> cancels = parallel(12, n -> post("/api/admin/orders/" + id + "/cancel", admin,
                "{\"reason\":\"Local checkout refund\"}"));
        assertEquals(1, count(cancels, 200));
        assertEquals(11, count(cancels, 409));
        // A worker can restart after the original provider response was lost and the resend window has expired.
        await(() -> jdbc.sql("""
                update order_payments set closing_at = now() - interval '21 hours', failures = 0, due_at = now()
                where order_id = :id and state = 'CAPTURED' and failures > 0 and lease_token is null
                """).param("id", id).update(), 1);
        await(() -> get(paymentPath(id), customer).read("$.state"), "REFUNDED");
        assertEquals(1, checkout.refunds.get());
        assertEquals("CANCELLED", status(id, customer));
    }

    @Test
    void customerCancelAndShopAcceptRaceHaveOneFinancialOutcome() throws Exception {
        Stocked stocked = stockedShop(adminToken(), 1_200, 12);
        String customer = customer();
        long id = order(customer, stocked.shopId(), item(stocked.rowId(), 2)).id();
        StripeFixture.Checkout checkout = authorize(id, customer);

        var race = parallel(2, n -> n == 0
                ? post("/api/orders/" + id + "/cancel", customer, null)
                : post("/api/shops/me/orders/" + id + "/accept", stocked.shop(), null));
        assertEquals(1, count(race, 200));
        assertEquals(1, count(race, 409));
        boolean cancelled = race.getFirst().status() == 200;
        await(() -> get(paymentPath(id), customer).read("$.state"), cancelled ? "VOIDED" : "CAPTURED");
        assertEquals(cancelled ? 1 : 0, checkout.voids.get());
        assertEquals(cancelled ? 0 : 1, checkout.captures.get());
        assertEquals(cancelled ? 12 : 10, stockOf(stocked.shop(), stocked.rowId()));
    }

    @Test
    void rejectedOrLateAuthorizationReleasesItsHoldAndStock() throws Exception {
        Stocked stocked = stockedShop(adminToken(), 700, 12);
        String customer = customer();
        long rejectedId = order(customer, stocked.shopId(), item(stocked.rowId(), 2)).id();
        var rejected = authorize(rejectedId, customer);
        assertEquals(200, post("/api/shops/me/orders/" + rejectedId + "/reject", stocked.shop(), null).status());
        await(() -> get(paymentPath(rejectedId), customer).read("$.state"), "VOIDED");
        assertEquals(1, rejected.voids.get());

        long lateId = order(customer, stocked.shopId(), item(stocked.rowId(), 2)).id();
        var late = opened(lateId, customer);
        jdbc.sql("update order_payments set expires_at = now() - interval '2 seconds' where order_id = :id")
                .param("id", lateId).update();
        late.authorize();
        assertEquals(204, webhook(event(late.session, false)).status());
        await(() -> status(lateId, customer), "EXPIRED");
        await(() -> get(paymentPath(lateId), customer).read("$.state"), "VOIDED");
        assertEquals(1, late.voids.get());
        assertEquals(12, stockOf(stocked.shop(), stocked.rowId()));
    }

    @Test
    void aDelayedSignedCompletionStillStartsTheShopWindow() throws Exception {
        var stocked = stockedShop(adminToken(), 700, 12);
        String customer = customer();
        long id = order(customer, stocked.shopId(), item(stocked.rowId(), 2)).id();
        var checkout = opened(id, customer);
        checkout.authorize();
        jdbc.sql("""
                update order_payments set expires_at = now() - interval '15 seconds', due_at = now()
                where order_id = :id
                """).param("id", id).update();
        orders.expireOverdue();
        assertEquals("PAYMENT_PENDING", status(id, customer));
        assertEquals(10, stockOf(stocked.shop(), stocked.rowId()));
        assertEquals(204, webhook(event(checkout.session, false, Instant.now().minusSeconds(20))).status());
        await(() -> status(id, customer), "PLACED");
        var placedAt = Instant.parse(get("/api/orders/" + id, customer).read("$.createdAt"));
        assertTrue(placedAt.isAfter(Instant.now().minusSeconds(10)));
        assertEquals(0, checkout.voids.get());
    }

    @Test
    void adminCanCancelBeforeCaptureAndReleaseTheHold() throws Exception {
        var stocked = stockedShop(adminToken(), 900, 8);
        String customer = customer();
        long id = order(customer, stocked.shopId(), item(stocked.rowId(), 2)).id();
        var checkout = authorize(id, customer);
        jdbc.sql("update order_payments set lease_until = now() + interval '1 hour' where order_id = :id")
                .param("id", id).update();
        assertEquals("CAPTURE_PENDING", post("/api/shops/me/orders/" + id + "/accept", stocked.shop(), null).read("$.status"));
        assertEquals("CANCELLED", adminCancel(id).read("$.status"));
        jdbc.sql("update order_payments set lease_until = null where order_id = :id").param("id", id).update();
        await(() -> get(paymentPath(id), customer).read("$.state"), "VOIDED");
        assertEquals(1, checkout.voids.get());
        assertEquals(0, checkout.captures.get());
        assertEquals(8, stockOf(stocked.shop(), stocked.rowId()));
    }

    @Test
    void adminCancellationDuringAProviderCaptureRefundsOnce() throws Exception {
        var stocked = stockedShop(adminToken(), 900, 8);
        String customer = customer();
        long id = order(customer, stocked.shopId(), item(stocked.rowId(), 2)).id();
        var checkout = authorize(id, customer);
        var pause = new StripeFixture.PauseCapture();
        checkout.pauseCapture = pause;
        try {
            assertEquals(200, post("/api/shops/me/orders/" + id + "/accept", stocked.shop(), null).status());
            assertTrue(pause.requested().await(8, TimeUnit.SECONDS));
            assertEquals("CANCELLED", adminCancel(id).read("$.status"));
        } finally {
            pause.proceed().countDown();
        }
        await(() -> get(paymentPath(id), customer).read("$.state"), "REFUNDED");
        assertEquals("CANCELLED", status(id, customer));
        assertEquals(1, checkout.captures.get());
        assertEquals(1, checkout.refunds.get());
        assertEquals(8, stockOf(stocked.shop(), stocked.rowId()));
    }

    @Test
    void preservesConfirmedProviderCaptureWhenTheOrderWriteKeepsFailing() throws Exception {
        String admin = adminToken();
        var stocked = stockedShop(admin, 900, 8);
        String customer = customer();
        long id = order(customer, stocked.shopId(), item(stocked.rowId(), 2)).id();
        var checkout = authorize(id, customer);
        var path = "/api/admin/orders/" + id + "/payment";
        jdbc.sql("alter table orders add constraint reject_test_capture check (id <> " + id + " or status <> 'ACCEPTED')").update();
        try {
            assertEquals(200, post("/api/shops/me/orders/" + id + "/accept", stocked.shop(), null).status());
            await(() -> get(path, admin).read("$.chargeId"), checkout.charge);
            checkout.intentUnavailable = true;
            await(() -> jdbc.sql("""
                    update order_payments set capture_until = now() - interval '16 minutes', due_at = now()
                    where order_id = :id and state = 'CAPTURING' and failures > 0 and lease_token is null
                    """).param("id", id).update(), 1);
            await(() -> get(path, admin).read("$.state"), "REVIEW_REQUIRED");
            var details = get(path, admin);
            assertEquals("LOCAL_FINALIZATION_FAILED", details.read("$.reviewReason"));
            assertEquals("CAPTURED", details.read("$.reviewProviderState"));
            assertEquals(checkout.charge, details.read("$.chargeId"));
            assertEquals(1, checkout.captures.get());
            assertEquals("CAPTURE_PENDING", status(id, customer));
            assertEquals(409, post("/api/shops/me/orders/" + id + "/ready", stocked.shop(), null).status());
        } finally {
            jdbc.sql("alter table orders drop constraint reject_test_capture").update();
        }
    }

    private Res adminCancel(long id) {
        Res response = post("/api/admin/orders/" + id + "/cancel", adminToken(), "{\"reason\":\"Sandbox test cancellation\"}");
        assertEquals(200, response.status(), response.body());
        return response;
    }

    @Test
    void providerAmountOrCurrencyMismatchBlocksFulfillment() throws Exception {
        Stocked stocked = stockedShop(adminToken(), 700, 12);
        String customer = customer();
        long id = order(customer, stocked.shopId(), item(stocked.rowId(), 1)).id();
        var checkout = opened(id, customer);
        checkout.wrongCurrency = true;
        checkout.authorize();
        assertEquals(204, webhook(event(checkout.session, false)).status());
        await(() -> status(id, customer), "EXPIRED");
        assertEquals("REVIEW_REQUIRED", get(paymentPath(id), customer).read("$.state"));
        assertEquals(0, checkout.captures.get());
        assertEquals(12, stockOf(stocked.shop(), stocked.rowId()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aStuckCaptureEscalatesAndReleasesStock(boolean providerUnavailable) throws Exception {
        Stocked stock = stockedShop(adminToken(), 900, 8);
        String customer = customer();
        long id = order(customer, stock.shopId(), item(stock.rowId(), 2)).id();
        var checkout = authorize(id, customer);
        checkout.intentState = "processing";
        checkout.intentUnavailable = providerUnavailable;

        jdbc.sql("update order_payments set lease_until = now() + interval '1 hour' where order_id = :id")
                .param("id", id).update();
        assertEquals("CAPTURE_PENDING", post("/api/shops/me/orders/" + id + "/accept", stock.shop(), null).read("$.status"));
        assertEquals(409, post("/api/shops/me/orders/" + id + "/ready", stock.shop(), null).status());
        jdbc.sql("""
                update order_payments set lease_until = null, capture_until = now() - interval '16 minutes'
                where order_id = :id
                """).param("id", id).update();

        String reason = providerUnavailable ? "STRIPE_API_RETRY_DEADLINE" : "STRIPE_RESULT_UNRESOLVED";
        await(() -> get(paymentPath(id), customer).read("$.state"), "REVIEW_REQUIRED");
        assertEquals("EXPIRED", status(id, customer));
        assertEquals(8, stockOf(stock.shop(), stock.rowId()));
        assertEquals(0, checkout.captures.get());
        assertEquals(0, checkout.voids.get());
        assertEquals(403, get("/api/admin/stripe/payment-reviews", customer).status());
        var reviews = get("/api/admin/stripe/payment-reviews", adminToken());
        assertEquals(List.of(reason), reviews.<List<String>>read("$[?(@.orderId == " + id + ")].reason"));
    }

    @Test
    void anExpiredLeaseIsReclaimedBeforeFulfillmentCanProceed() throws Exception {
        var stock = stockedShop(adminToken(), 900, 8);
        String customer = customer();
        long id = order(customer, stock.shopId(), item(stock.rowId(), 1)).id();
        var checkout = authorize(id, customer);
        jdbc.sql("""
                update order_payments set lease_until = now() + interval '1 hour', lease_token = :lease
                where order_id = :id
                """).param("id", id).param("lease", UUID.randomUUID()).update();
        assertEquals("CAPTURE_PENDING",
                post("/api/shops/me/orders/" + id + "/accept", stock.shop(), null).read("$.status"));
        assertEquals(409, post("/api/shops/me/orders/" + id + "/ready", stock.shop(), null).status());
        assertEquals(0, checkout.captures.get());
        jdbc.sql("update order_payments set lease_until = now() - interval '1 second' where order_id = :id")
                .param("id", id).update();
        await(() -> status(id, customer), "ACCEPTED");
        assertEquals(1, checkout.captures.get());
    }

    @Test
    void twentyFourCustomersCanRetryNinetySixSimultaneousCheckouts() throws Exception {
        int customers = 24;
        Stocked stock = stockedShop(adminToken(), 850, customers);
        int opensBefore = stripe.opens.get();
        List<String> tokens = IntStream.range(0, customers).mapToObj(n -> customer()).toList();
        List<String> requests = IntStream.range(0, customers)
                .mapToObj(n -> orderJson(stock.shopId(), item(stock.rowId(), 1))).toList();
        long started = System.nanoTime();
        long[] latency = new long[customers * 4];
        List<Res> burst = parallel(latency.length, n -> {
            int who = n % customers;
            long start = System.nanoTime();
            Res result = post("/api/orders", tokens.get(who), requests.get(who));
            latency[n] = (System.nanoTime() - start) / 1_000_000;
            return result;
        });
        assertEquals(burst.size(), count(burst, 200) + count(burst, 201) + count(burst, 503));
        var orders = new HashSet<Long>();
        for (int i = 0; i < customers; i++) {
            Res replay = post("/api/orders", tokens.get(i), requests.get(i));
            assertTrue(replay.status() == 200 || replay.status() == 201, replay.body());
            long id = replay.id();
            assertTrue(orders.add(id));
            for (int attempt = i; attempt < burst.size(); attempt += customers) {
                Res result = burst.get(attempt);
                if (result.status() != 503) {
                    assertEquals(id, result.id());
                }
            }
        }
        List<Long> orderIds = new java.util.ArrayList<>();
        List<StripeFixture.Checkout> providerCheckouts = new java.util.ArrayList<>();
        for (int i = 0; i < customers; i++) {
            var result = get("/api/orders", tokens.get(i));
            assertEquals(1, result.<List<?>>read("$.content").size());
            long id = result.<Number>read("$.content[0].id").longValue();
            orderIds.add(id);
            providerCheckouts.add(opened(id, tokens.get(i)));
        }
        assertEquals(customers, stripe.opens.get() - opensBefore);
        assertEquals(0, stockOf(stock.shop(), stock.rowId()));
        assertEquals(409, post("/api/orders", customer(), orderJson(stock.shopId(), item(stock.rowId(), 1))).status());
        assertTrue(stripe.maxOpenConcurrency.get() <= 4);
        var notifications = parallel(customers, i -> {
            var checkout = providerCheckouts.get(i);
            checkout.authorize();
            return webhookUnchecked(event(checkout.session, false));
        });
        assertEquals(customers, count(notifications, 204));
        for (int i = 0; i < customers; i++) {
            int who = i;
            await(() -> status(orderIds.get(who), tokens.get(who)), "PLACED");
        }
        var accepted = parallel(customers, i ->
                post("/api/shops/me/orders/" + orderIds.get(i) + "/accept", stock.shop(), null));
        assertEquals(customers, count(accepted, 200));
        for (int i = 0; i < customers; i++) {
            int who = i;
            await(() -> status(orderIds.get(who), tokens.get(who)), "ACCEPTED");
            assertEquals(1, providerCheckouts.get(i).captures.get());
        }
        Arrays.sort(latency);
        System.out.printf("Payment load: %d concurrent requests, %d customers, p95 %d ms, %d HTTP 503, "
                + "%d unique Stripe sessions and captures, max %d concurrent provider opens, %d ms including recovery and capture%n",
                burst.size(), customers, latency[(int) Math.ceil(0.95 * latency.length) - 1], count(burst, 503),
                stripe.opens.get() - opensBefore, stripe.maxOpenConcurrency.get(), (System.nanoTime() - started) / 1_000_000);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void adminQueuesOneConnectTransferAndReconcilesPartialReversals(boolean partiallyReversed) throws Exception {
        Scene s = scene(12);
        DriverAt driver = driverNear(s);
        Res placed = post("/api/orders", s.customer(),
                orderJsonWith(s.shopId(), UUID.randomUUID().toString(), doorNear(s), item(s.rowId(), 2)));
        assertEquals(201, placed.status(), placed.body());
        long orderId = placed.id();
        var provider = authorize(orderId, s.customer());
        assertEquals(200, post("/api/shops/me/orders/" + orderId + "/accept", s.shop(), null).status());
        await(() -> status(orderId, s.customer()), "ACCEPTED");
        long shopPay = get("/api/shops/me/orders/" + orderId, s.shop()).<Number>read("$.payoutCents").longValue();
        String shopTransfer = "/api/admin/stripe/orders/" + orderId + "/transfer";
        assertEquals(403, post(shopTransfer, s.shop(), null).status());
        assertEquals(409, post(shopTransfer, s.admin(), null).status());
        assertEquals(204, put("/api/admin/stripe/recipients/SHOP/" + s.shopId(), s.admin(),
                "{\"accountId\":\"acct_shopFixture" + s.shopId() + "0000\"}").status());
        assertEquals(204, put("/api/admin/stripe/recipients/DRIVER/" + driver.userId(), s.admin(),
                "{\"accountId\":\"acct_driverFixture" + driver.userId() + "0000\"}").status());
        assertEquals(409, put("/api/admin/stripe/recipients/DRIVER/" + driver.userId(), s.admin(),
                "{\"accountId\":\"acct_otherFixture01\"}").status());
        deliver(s, driver, orderId);

        stripe.loseTransferResponse.set(true);
        int transfersBefore = stripe.transfers.get();
        var shopTransfers = parallel(12, n -> post(shopTransfer, s.admin(), null));
        assertEquals(12, count(shopTransfers, 202));
        String transferId = shopTransfers.getFirst().read("$.id");
        for (Res response : shopTransfers) {
            assertEquals(transferId, response.read("$.id"));
            assertEquals(shopPay, response.<Number>read("$.amountCents").longValue());
        }
        UUID job = UUID.fromString(transferId);
        await(() -> jdbc.sql("""
                update stripe_transfers set due_at = now() where id = :id and lease_token is null
                    and first_attempt_at is not null and state = 'QUEUED'
                """).param("id", job).update(), 1);
        await(() -> get("/api/admin/stripe/transfers/" + transferId, s.admin()).read("$.state"), "TRANSFERRED");
        assertEquals(transfersBefore + 1, stripe.transfers.get());
        assertEquals(provider.charge, jdbc.sql("select source_charge from stripe_transfers where id = :id")
                .param("id", UUID.fromString(transferId)).query(String.class).single());

        jdbc.sql("""
                update driver_engagements set ended_at = ended_at - interval '14 days', pay_cents = 500
                where order_id = :id
                """).param("id", orderId).update();
        String period = get("/api/drivers/me/pay-statements?count=2", driver.token()).read("$[1].period.start");
        String driverTransfer = "/api/admin/stripe/driver-payouts/" + driver.userId() + "/" + period;
        if (partiallyReversed) {
            stripe.loseTransferResponse.set(true);
            stripe.partiallyReverseTransferAfterLoss.set(true);
        }
        var all = parallel(10, n -> post(driverTransfer, s.admin(), null));
        assertEquals(10, count(all, 202));
        String paid = all.getFirst().read("$.id");
        assertTrue(all.stream().allMatch(res -> paid.equals(res.read("$.id"))));
        String transferPath = "/api/admin/stripe/transfers/" + paid;
        if (partiallyReversed) {
            await(() -> jdbc.sql("""
                    update stripe_transfers set due_at = now() where id = :id and lease_token is null
                        and first_attempt_at is not null and state = 'QUEUED'
                    """).param("id", UUID.fromString(paid)).update(), 1);
            await(() -> get(transferPath, s.admin()).read("$.state"), "REVIEW_REQUIRED");
            assertEquals("UNEXPECTED_STRIPE_TRANSFER", get(transferPath, s.admin()).read("$.reviewReason"));
            assertTrue(jdbc.sql("select amount_cents from driver_payouts where driver_id = :id")
                    .param("id", driver.userId()).query(Integer.class).optional().isEmpty());
        } else {
            await(() -> get(transferPath, s.admin()).read("$.state"), "TRANSFERRED");
            assertEquals(500, jdbc.sql("select amount_cents from driver_payouts where driver_id = :id")
                    .param("id", driver.userId()).query(Integer.class).single());
        }
        assertEquals(transfersBefore + 2, stripe.transfers.get());
    }

    @Test
    void aShopTransferStopsForAnExternallyRefundedCharge() throws Exception {
        Scene s = scene(10);
        DriverAt driver = driverNear(s);
        var placed = post("/api/orders", s.customer(),
                orderJsonWith(s.shopId(), UUID.randomUUID().toString(), doorNear(s), item(s.rowId(), 1)));
        assertEquals(201, placed.status(), placed.body());
        long id = placed.id();
        var provider = authorize(id, s.customer());
        assertEquals(200, post("/api/shops/me/orders/" + id + "/accept", s.shop(), null).status());
        await(() -> status(id, s.customer()), "ACCEPTED");
        deliver(s, driver, id);
        assertEquals(204, put("/api/admin/stripe/recipients/SHOP/" + s.shopId(), s.admin(),
                "{\"accountId\":\"acct_refundedShop01\"}").status());

        provider.externalRefundedCents = 100;
        int before = stripe.transfers.get();
        Res queued = post("/api/admin/stripe/orders/" + id + "/transfer", s.admin(), null);
        assertEquals(202, queued.status(), queued.body());
        String transfer = queued.read("$.id");
        await(() -> get("/api/admin/stripe/transfers/" + transfer, s.admin()).read("$.state"), "REVIEW_REQUIRED");
        assertEquals(before, stripe.transfers.get());
        assertEquals(403, get("/api/admin/stripe/transfer-reviews", s.shop()).status());
        assertEquals(List.of("SHOP_CHARGE_INELIGIBLE"), get("/api/admin/stripe/transfer-reviews", s.admin())
                .<List<String>>read("$[?(@.id == '" + transfer + "')].reviewReason"));
    }

    @Test
    void aDeliveredOrderGetsOneSeparateTipAndDriverCreditAfterCapture() throws Exception {
        Scene scene = scene(10);
        DriverAt driver = driverNear(scene);
        var placed = post("/api/orders", scene.customer(),
                orderJsonWith(scene.shopId(), UUID.randomUUID().toString(), doorNear(scene), item(scene.rowId(), 1)));
        assertEquals(201, placed.status(), placed.body());
        long id = placed.id();
        String path = "/api/orders/" + id + "/tip/checkout";
        String tip = "{\"tipCents\":200}";
        var checkout = authorize(id, scene.customer());
        assertEquals(409, post(path, scene.customer(), tip).status());
        assertEquals(200, post("/api/shops/me/orders/" + id + "/accept", scene.shop(), null).status());
        await(() -> status(id, scene.customer()), "ACCEPTED");
        deliver(scene, driver, id);
        assertEquals(404, post(path, customer(), tip).status());
        assertEquals(409, post("/api/orders/" + id + "/tip", scene.customer(), tip).status());

        int opened = stripe.opens.get();
        var repeats = parallel(12, n -> post(path, scene.customer(), tip));
        assertEquals(12, count(repeats, 202));
        assertEquals(409, post(path, scene.customer(), "{\"tipCents\":500}").status());
        await(() -> get(path, scene.customer()).read("$.state"), "OPEN");
        assertEquals(0, get("/api/orders/" + id, scene.customer()).<Integer>read("$.tipCents"));
        String tipUrl = get(path, scene.customer()).read("$.checkoutUrl");
        var tipCheckout = stripe.session(tipUrl.substring(tipUrl.lastIndexOf('/') + 1));
        assertEquals(200, tipCheckout.amount);
        assertEquals(opened + 1, stripe.opens.get());
        tipCheckout.authorize();
        assertEquals(204, webhook(event(tipCheckout.session, false)).status());
        await(() -> get(path, scene.customer()).read("$.state"), "CAPTURED");
        assertEquals(1, checkout.captures.get());
        assertEquals(1, tipCheckout.captures.get());
        assertEquals(200, get("/api/orders/" + id, scene.customer()).<Integer>read("$.tipCents"));
        assertEquals(200, get("/api/drivers/me/pay-statements?count=1", driver.token()).<Integer>read("$[0].tipsCents"));
        assertEquals(202, post(path, scene.customer(), tip).status());
        assertEquals(409, post(path, scene.customer(), "{\"tipCents\":500}").status());
    }

    private void deliver(Scene s, DriverAt driver, long orderId) {
        assertEquals(200, post("/api/drivers/me/jobs/" + orderId + "/accept", driver.token(), null).status());
        assertEquals(200, post("/api/shops/me/orders/" + orderId + "/ready", s.shop(), null).status());
        assertEquals(200, post("/api/drivers/me/delivery/picked-up", driver.token(), null).status());
        String code = get("/api/orders/" + orderId, s.customer()).read("$.deliveryCode");
        assertEquals(200, post("/api/drivers/me/delivery/delivered", driver.token(), "{\"code\":\"" + code + "\"}").status());
    }

    private static String paymentPath(long id) {
        return "/api/orders/" + id + "/payment";
    }

    private String status(long id, String token) {
        return get("/api/orders/" + id, token).read("$.status");
    }

    private StripeFixture.Checkout opened(long id, String customer) {
        await(() -> get(paymentPath(id), customer).read("$.state"), "OPEN");
        String url = get(paymentPath(id), customer).read("$.checkoutUrl");
        var result = stripe.session(url.substring(url.lastIndexOf('/') + 1));
        assertNotNull(result);
        return result;
    }

    private StripeFixture.Checkout authorize(long id, String customer) throws Exception {
        var checkout = opened(id, customer);
        checkout.authorize();
        assertEquals(204, webhook(event(checkout.session, false)).status());
        await(() -> get(paymentPath(id), customer).read("$.state"), "AUTHORIZED");
        return checkout;
    }

    private static String event(String session, boolean live) {
        return event(session, live, Instant.now());
    }

    private static String event(String session, boolean live, Instant created) {
        return """
                {"id":"evt_local_checkout","type":"checkout.session.completed","livemode":%s,"created":%d,
                 "data":{"object":{"id":"%s","object":"checkout.session"}}}
                """.formatted(live, created.getEpochSecond(), session);
    }

    private Res webhookUnchecked(String payload) {
        try {
            return webhook(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Res webhook(String payload) throws Exception {
        return webhook(payload, signed(payload, Instant.now()), false);
    }

    private static String signed(String value, Instant instant) throws Exception {
        return Webhook.Signature.generateSignatureHeader(value, SIGNING_SECRET, instant.getEpochSecond());
    }

    private Res webhook(String payload, String signature, boolean chunked) throws Exception {
        var body = chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(
                        payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                : HttpRequest.BodyPublishers.ofString(payload);
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + WEBHOOK))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Stripe-Signature", signature)
                .POST(body).build(), HttpResponse.BodyHandlers.ofString());
        return new Res(response.statusCode(), response.body(), response.headers());
    }

    private static <T> void await(Supplier<T> status, T expected) {
        T actual = null;
        for (int i = 0; i < 350; i++) {
            actual = status.get();
            if (expected.equals(actual)) {
                return;
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        fail("Expected " + expected + " but observed " + actual);
    }
}
