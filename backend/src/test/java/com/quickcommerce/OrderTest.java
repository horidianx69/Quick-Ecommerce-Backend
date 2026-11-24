package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quickcommerce.order.OrderService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The order flow over real HTTP: placing, pricing, each status change, and who may see what. */
class OrderTest extends ApiTestBase {

    @Autowired
    OrderService orderService;

    @Test
    void placeAcceptReady() {
        String admin = adminToken();
        Stocked s = stockedShop(admin, 1000, 10);
        String customer = customer();

        Res placed = order(customer, s.shopId(), item(s.rowId(), 3));
        long id = placed.id();
        assertEquals("PLACED", placed.read("$.status"));
        assertEquals("Corner Stop", placed.read("$.shopName"));
        assertEquals(1499, placed.<Integer>read("$.items[0].unitPriceCents"), "shop price 10.00 x 1.5 = 15.00, down to 14.99");
        assertEquals(4497, placed.<Integer>read("$.subtotalCents"));
        assertEquals(599, placed.<Integer>read("$.deliveryFeeCents"), "items under $50");
        assertEquals(199, placed.<Integer>read("$.handlingFeeCents"));
        assertEquals(0, placed.<Integer>read("$.smallCartFeeCents"));
        assertEquals(4497 + 599 + 199, placed.<Integer>read("$.totalCents"));
        assertEquals(7, stockOf(s.shop(), s.rowId()), "3 units are held the moment the order is placed");

        // The customer never sees the shop's price, payout, address or location.
        for (String secret : List.of("shopPriceCents", "payoutCents", "12 Main St", "ownerId", "\"latitude\":49.19")) {
            assertFalse(placed.body().contains(secret), "customer view leaks " + secret + ": " + placed.body());
        }

        Res inbox = get("/api/shops/me/orders?status=PLACED", s.shop());
        assertEquals(1, inbox.<Integer>read("$.page.totalElements"));
        assertEquals((int) id, inbox.<Integer>read("$.content[0].id"));
        assertEquals(850, inbox.<Integer>read("$.content[0].items[0].unitPriceCents"), "the shop sees its payout: 10.00 less 15%");
        assertEquals(2550, inbox.<Integer>read("$.content[0].payoutCents"));
        // The shop never sees the customer's price or where the customer lives.
        for (String secret : List.of("customerPriceCents", "totalCents", "subtotalCents", "77 Customer Rd", "latitude", "customerId")) {
            assertFalse(inbox.body().contains(secret), "shop view leaks " + secret + ": " + inbox.body());
        }

        assertEquals(200, get("/api/orders/" + id, customer).status());
        assertEquals(1, get("/api/orders", customer).<Integer>read("$.page.totalElements"));

        // Steps cannot be skipped or repeated.
        String shopOrder = "/api/shops/me/orders/" + id;
        assertEquals(409, post(shopOrder + "/ready", s.shop(), null).status(), "not accepted yet");
        assertEquals("ACCEPTED", post(shopOrder + "/accept", s.shop(), null).read("$.status"));
        assertEquals(409, post(shopOrder + "/accept", s.shop(), null).status(), "already accepted");
        assertEquals(409, post(shopOrder + "/reject", s.shop(), null).status(), "too late to reject");
        assertEquals(409, post("/api/orders/" + id + "/cancel", customer, null).status(), "too late to cancel");
        assertEquals("READY", post(shopOrder + "/ready", s.shop(), null).read("$.status"));
        assertEquals(409, post(shopOrder + "/ready", s.shop(), null).status());

        assertEquals("READY", get("/api/orders/" + id, customer).read("$.status"));
        assertEquals(7, stockOf(s.shop(), s.rowId()), "an accepted order keeps its stock");
    }

    @Test
    void pricesFollowTheSheet() {
        // The five products from the pricing sheet: shop price -> shop paid (less 15%) -> customer (x1.5, down to .49/.99).
        String admin = adminToken();
        Stocked s = stockedShop(admin, 349, 10); // Monster
        long snickers = addStock(s.shop(), admin, 179, 10);
        long yogurt = addStock(s.shop(), admin, 829, 10);
        long lays = addStock(s.shop(), admin, 599, 10);
        long cookie = addStock(s.shop(), admin, 499, 10);

        Res placed = order(customer(), s.shopId(), item(s.rowId(), 1), item(snickers, 1), item(yogurt, 1), item(lays, 1),
                item(cookie, 1));
        assertEquals(List.of(249, 499, 699, 849, 1199), placed.<List<Integer>>read("$.items[*].unitPriceCents").stream()
                .sorted().toList(), "4.99, 2.49, 11.99, 8.49, 6.99 as on the sheet");
        assertEquals(3495, placed.<Integer>read("$.subtotalCents"), "34.95");
        assertEquals(3495 + 599 + 199, placed.<Integer>read("$.totalCents"), "34.95 + 5.99 delivery + 1.99 handling");

        Res forAdmin = get("/api/admin/orders/" + placed.id(), admin);
        assertEquals(List.of(152, 296, 424, 509, 704), forAdmin.<List<Integer>>read("$.items[*].shopPriceCents").stream()
                .sorted().toList(), "2.96, 1.52, 7.04, 5.09, 4.24: each rounded down to the cent");
        assertEquals(2085, forAdmin.<Integer>read("$.shopPayoutCents"));
        assertEquals(3495 - 2085, forAdmin.<Integer>read("$.itemMarginCents"), "we keep 14.10 on the items");

        // A price so low that rounding down would sell at a loss rounds up instead.
        long penny = addStock(s.shop(), admin, 1, 10);
        Res tiny = order(customer(), s.shopId(), item(penny, 1));
        assertEquals(49, tiny.<Integer>read("$.items[0].unitPriceCents"));
        assertEquals(299, tiny.<Integer>read("$.smallCartFeeCents"), "under $15: small-cart fee");
    }

    @Test
    void aRetryWithTheSameKeyReturnsTheFirstOrder() {
        Stocked s = stockedShop(adminToken(), 1000, 10);
        String customer = customer();
        String body = orderJsonWith(s.shopId(), "retry-key-12345", NEAR_ADDRESS, item(s.rowId(), 4));

        Res first = post("/api/orders", customer, body);
        assertEquals(201, first.status(), first.body());
        Res retry = post("/api/orders", customer, body);
        assertEquals(200, retry.status(), "a replay is 200, not 201");
        assertEquals(first.id(), retry.id());
        Res changed = post("/api/orders", customer,
                orderJsonWith(s.shopId(), "retry-key-12345", NEAR_ADDRESS, item(s.rowId(), 3)));
        assertEquals(409, changed.status(), changed.body());
        assertEquals(6, stockOf(s.shop(), s.rowId()), "stock is taken once, not twice");
        assertEquals(1, get("/api/orders", customer).<Integer>read("$.page.totalElements"));

        Res other = post("/api/orders", customer(), body);
        assertEquals(201, other.status(), "the key only repeats per customer: " + other.body());
    }

    @Test
    void cartsAboveTheMaximumTotalCannotTakeStock() {
        String admin = adminToken();
        Stocked s = stockedShop(admin, 1_000_000, 99);
        String[] lines = new String[15];
        lines[0] = item(s.rowId(), 99);
        for (int i = 1; i < lines.length; i++) {
            lines[i] = item(addStock(s.shop(), admin, 1_000_000, 99), 99);
        }
        String customer = customer();
        String body = orderJsonWith(s.shopId(), "large-cart-key", NEAR_ADDRESS, lines);
        Res quote = post("/api/orders/quote", customer, body.replace("\"idempotencyKey\":\"large-cart-key\",", ""));
        assertEquals(400, quote.status(), quote.body());
        Res placement = post("/api/orders", customer, body);
        assertEquals(400, placement.status(), placement.body());
        assertEquals(99, stockOf(s.shop(), s.rowId()));
        assertEquals(0, get("/api/orders", customer).<Integer>read("$.page.totalElements"));
    }

    @Test
    void quoteAndCheckoutRejectAListingRemovedFromTheApprovedCatalog() {
        String admin = adminToken();
        Stocked s = stockedShop(admin, 1000, 10);
        long productId = approvedProduct(admin);
        long listingId = post("/api/shops/me/products", s.shop(), addJson(productId, 500, 10)).id();
        String customer = customer();
        String body = orderJsonWith(s.shopId(), "stale-catalog-key", NEAR_ADDRESS, item(s.rowId(), 1), item(listingId, 1));
        String quoteBody = body.replace("\"idempotencyKey\":\"stale-catalog-key\",", "");
        assertEquals(200, post("/api/orders/quote", customer, quoteBody).status());
        assertEquals(200, put("/api/admin/catalog/" + productId, admin, """
                {"name":"Withdrawn product","category":"Pantry","taxCategory":"TAXABLE","status":"REJECTED"}
                """).status());

        for (Res response : List.of(post("/api/orders/quote", customer, quoteBody), post("/api/orders", customer, body))) {
            assertEquals(409, response.status(), response.body());
            assertEquals(listingId, response.<Number>read("$.shopProductId").longValue());
        }
        assertEquals(10, stockOf(s.shop(), s.rowId()), "the earlier line was rolled back");
        assertEquals(10, stockOf(s.shop(), listingId));
    }

    @Test
    void rejectedCancelledAndExpiredOrdersGiveTheirStockBack() {
        Stocked s = stockedShop(adminToken(), 1000, 10);
        String customer = customer();

        long rejected = order(customer, s.shopId(), item(s.rowId(), 4)).id();
        assertEquals(6, stockOf(s.shop(), s.rowId()));
        assertEquals("REJECTED", post("/api/shops/me/orders/" + rejected + "/reject", s.shop(), null).read("$.status"));
        assertEquals(10, stockOf(s.shop(), s.rowId()));
        assertEquals("REJECTED", get("/api/orders/" + rejected, customer).read("$.status"));

        long cancelled = order(customer, s.shopId(), item(s.rowId(), 5)).id();
        assertEquals("CANCELLED", post("/api/orders/" + cancelled + "/cancel", customer, null).read("$.status"));
        assertEquals(10, stockOf(s.shop(), s.rowId()));
        assertEquals(409, post("/api/orders/" + cancelled + "/cancel", customer, null).status(), "cannot cancel twice");
        assertEquals(409, post("/api/shops/me/orders/" + cancelled + "/accept", s.shop(), null).status());
        assertEquals(10, stockOf(s.shop(), s.rowId()), "a refused second cancel returns nothing extra");

        // The shop never answers: the expiry job ends the order. (Run by hand here with a cutoff in the future.)
        long ignored = order(customer, s.shopId(), item(s.rowId(), 2)).id();
        assertEquals(8, stockOf(s.shop(), s.rowId()));
        assertTrue(orderService.expirePlacedBefore(Instant.now().plusSeconds(5)) >= 1);
        assertEquals("EXPIRED", get("/api/orders/" + ignored, customer).read("$.status"));
        assertEquals(10, stockOf(s.shop(), s.rowId()));
        assertEquals(409, post("/api/shops/me/orders/" + ignored + "/accept", s.shop(), null).status());
        assertEquals(0, orderService.expirePlacedBefore(Instant.now().minusSeconds(3600)),
                "a fresh order is not expired by the real job");
    }

    @Test
    void theShopCanAcceptAnOrderWithoutAnItemItDoesNotHave() {
        String admin = adminToken();
        Stocked s = stockedShop(admin, 1000, 10);
        long rowB = addStock(s.shop(), admin, 500, 8);
        String customer = customer();

        Res placed = order(customer, s.shopId(), item(s.rowId(), 2), item(rowB, 3));
        long id = placed.id();
        List<Integer> bLine = placed.read("$.items[?(@.unitPriceCents == 749)].id");
        long itemB = bLine.get(0);
        List<Integer> allItems = placed.read("$.items[*].id");
        String accept = "/api/shops/me/orders/" + id + "/accept";

        assertEquals(400, post(accept, s.shop(), "{\"unavailableItemIds\":[999999999]}").status(), "not an item of this order");
        assertEquals(400, post(accept, s.shop(), "{\"unavailableItemIds\":" + allItems + "}").status(),
                "everything unavailable is a reject, not an accept");
        assertEquals("PLACED", get("/api/orders/" + id, customer).read("$.status"), "refused accepts change nothing");

        Res accepted = post(accept, s.shop(), "{\"unavailableItemIds\":[" + itemB + "]}");
        assertEquals(200, accepted.status(), accepted.body());
        assertEquals(1700, accepted.<Integer>read("$.payoutCents"), "the shop is paid only for what it supplies: 2 x 8.50");

        Res forCustomer = get("/api/orders/" + id, customer);
        assertEquals(List.of(true), forCustomer.<List<Boolean>>read("$.items[?(@.id == " + itemB + ")].unavailable"));
        assertEquals(2998, forCustomer.<Integer>read("$.subtotalCents"), "the customer does not pay for the missing item");
        assertEquals(499, forCustomer.<Integer>read("$.deliveryFeeCents"),
                "the fee was set at checkout ($52.45 of items) and does not go up when an item is missing");
        assertEquals(2998 + 499 + 199, forCustomer.<Integer>read("$.totalCents"));

        assertEquals(0, stockOf(s.shop(), rowB), "the count on file was wrong, so the product goes out of stock");
        assertEquals(8, stockOf(s.shop(), s.rowId()), "the other product is untouched");
    }

    @Test
    void badOrdersAreRefusedAndTakeNoStock() {
        String admin = adminToken();
        Stocked s = stockedShop(admin, 1000, 10);
        long rowB = addStock(s.shop(), admin, 500, 2);
        Stocked otherShop = stockedShop(admin, 700, 10);
        String customer = customer();
        String one = item(s.rowId(), 1);

        assertEquals(400, post("/api/orders", customer, orderJson(s.shopId())).status(), "no items");
        assertEquals(400, post("/api/orders", customer, orderJson(s.shopId(), item(s.rowId(), 0))).status());
        assertEquals(400, post("/api/orders", customer, orderJson(s.shopId(), item(s.rowId(), 100))).status());
        assertEquals(400, post("/api/orders", customer, orderJson(s.shopId(), one, item(s.rowId(), 2))).status(), "same product twice");
        assertEquals(400, post("/api/orders", customer, orderJsonWith(s.shopId(), "short", NEAR_ADDRESS, one)).status(), "key too short");
        assertEquals(400, post("/api/orders", customer,
                orderJsonWith(s.shopId(), UUID.randomUUID().toString(), NEAR_ADDRESS.replace("V3T 2B2", "12345"), one)).status());
        assertEquals(400, post("/api/orders", customer,
                "{\"shopId\":%d,\"idempotencyKey\":\"%s\",\"items\":[%s]}".formatted(s.shopId(), UUID.randomUUID(), one)).status(),
                "no address");

        Res tooFar = post("/api/orders", customer,
                orderJsonWith(s.shopId(), UUID.randomUUID().toString(), NEAR_ADDRESS.replace("49.2", "49.5"), one));
        assertEquals(400, tooFar.status(), "about 34 km away: " + tooFar.body());

        assertEquals(404, post("/api/orders", customer, orderJson(999999999, one)).status(), "no such shop");
        String pendingOwner = shopOwner();
        long pendingShop = get("/api/shops/me", pendingOwner).id();
        long pendingRow = addStock(pendingOwner, admin, 400, 5);
        assertEquals(404, post("/api/orders", customer, orderJson(pendingShop, item(pendingRow, 1))).status(),
                "a shop that is not approved cannot take orders");

        Res wrongShop = post("/api/orders", customer, orderJson(s.shopId(), one, item(otherShop.rowId(), 1)));
        assertEquals(400, wrongShop.status(), wrongShop.body());
        assertEquals((int) otherShop.rowId(), wrongShop.<Integer>read("$.shopProductId"));

        // Second line asks for more than there is: the whole order fails and the first line's stock comes back.
        Res tooMany = post("/api/orders", customer, orderJson(s.shopId(), item(s.rowId(), 4), item(rowB, 3)));
        assertEquals(409, tooMany.status(), tooMany.body());
        assertEquals((int) rowB, tooMany.<Integer>read("$.shopProductId"), "the error names the line that failed");

        assertEquals(10, stockOf(s.shop(), s.rowId()), "no refused order took any stock");
        assertEquals(2, stockOf(s.shop(), rowB));
        assertEquals(10, stockOf(otherShop.shop(), otherShop.rowId()));
        assertEquals(0, get("/api/orders", customer).<Integer>read("$.page.totalElements"));

        // A price sent by the client is ignored.
        Res sneaky = post("/api/orders", customer, orderJson(s.shopId(),
                "{\"shopProductId\":%d,\"quantity\":1,\"priceCents\":1,\"customerPriceCents\":1,\"unitPriceCents\":1}".formatted(s.rowId())));
        assertEquals(201, sneaky.status(), sneaky.body());
        assertEquals(1499, sneaky.<Integer>read("$.items[0].unitPriceCents"));

        // A suspended shop stops taking orders at once.
        put("/api/admin/shops/" + s.shopId() + "/status", admin, "{\"status\":\"SUSPENDED\"}");
        assertEquals(404, post("/api/orders", customer(), orderJson(s.shopId(), one)).status());
    }

    @Test
    void onlyTheOwnersOfAnOrderCanSeeOrChangeIt() {
        String admin = adminToken();
        Stocked s = stockedShop(admin, 1000, 10);
        Stocked otherShop = stockedShop(admin, 700, 10);
        String customer = customer();
        String otherCustomer = customer();
        long id = order(customer, s.shopId(), item(s.rowId(), 1)).id();

        assertEquals(404, get("/api/orders/" + id, otherCustomer).status());
        assertEquals(404, post("/api/orders/" + id + "/cancel", otherCustomer, null).status());
        assertEquals(0, get("/api/orders", otherCustomer).<Integer>read("$.page.totalElements"));

        String foreign = "/api/shops/me/orders/" + id;
        assertEquals(404, get(foreign, otherShop.shop()).status());
        assertEquals(404, post(foreign + "/accept", otherShop.shop(), null).status());
        assertEquals(404, post(foreign + "/reject", otherShop.shop(), null).status());
        assertEquals(404, post(foreign + "/ready", otherShop.shop(), null).status());
        assertEquals(0, get("/api/shops/me/orders", otherShop.shop()).<Integer>read("$.page.totalElements"));

        // Each role is kept to its own side.
        assertEquals(403, get("/api/orders", s.shop()).status());
        assertEquals(403, post("/api/orders", s.shop(), orderJson(s.shopId(), item(s.rowId(), 1))).status());
        assertEquals(403, get("/api/shops/me/orders", customer).status());
        assertEquals(403, post(foreign + "/accept", customer, null).status(), "a customer cannot accept their own order");
        assertEquals(403, get("/api/shops/me/products", customer).status());
        assertEquals(403, get("/api/orders", admin).status());
        assertEquals(401, get("/api/orders", null).status());
        assertEquals(401, post("/api/orders", null, orderJson(s.shopId(), item(s.rowId(), 1))).status());

        assertEquals("PLACED", get("/api/orders/" + id, customer).read("$.status"), "nothing above changed the order");
        assertEquals(9, stockOf(s.shop(), s.rowId()));
    }

    @Test
    void aCustomerCannotHoldMoreThanThreeUnansweredOrders() {
        Stocked s = stockedShop(adminToken(), 1000, 50);
        String customer = customer();
        long first = order(customer, s.shopId(), item(s.rowId(), 1)).id();
        order(customer, s.shopId(), item(s.rowId(), 1));
        order(customer, s.shopId(), item(s.rowId(), 1));

        assertEquals(429, post("/api/orders", customer, orderJson(s.shopId(), item(s.rowId(), 1))).status());
        assertEquals(47, stockOf(s.shop(), s.rowId()), "the refused 4th order took no stock");

        post("/api/shops/me/orders/" + first + "/accept", s.shop(), null);
        assertEquals(201, post("/api/orders", customer, orderJson(s.shopId(), item(s.rowId(), 1))).status(),
                "once the shop answers one, the customer can order again");
    }

    @Test
    void removingAProductDoesNotBreakOrdersThatHoldIt() {
        Stocked s = stockedShop(adminToken(), 1000, 10);
        String customer = customer();
        Res placed = order(customer, s.shopId(), item(s.rowId(), 2));
        String name = placed.read("$.items[0].name");

        assertEquals(204, delete("/api/shops/me/products/" + s.rowId(), s.shop()).status());
        Res rejected = post("/api/shops/me/orders/" + placed.id() + "/reject", s.shop(), null);
        assertEquals(200, rejected.status(), rejected.body());
        assertEquals(name, get("/api/orders/" + placed.id(), customer).read("$.items[0].name"),
                "the order keeps its own copy of the item");
    }
}
