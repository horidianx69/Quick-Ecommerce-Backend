package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Admin's tools over real HTTP: prices, opening hours, the order board, cancelling, assigning drivers, suspending accounts. */
class AdminTest extends ApiTestBase {

    static final String DEFAULT_SETTINGS = "{\"defaultMarkupPercent\":150,\"deliveryFeeCents\":499,\"driverPayCents\":499}";

    /** Settings are shared by every test, so put them back after each one. */
    @AfterEach
    void restoreSettings() {
        assertEquals(200, put("/api/admin/settings", adminToken(), DEFAULT_SETTINGS).status());
    }

    static String tag() {
        return "zq" + UUID.randomUUID().toString().substring(0, 8);
    }

    long productIn(String admin, String name, String category) {
        Res res = post("/api/admin/catalog", admin,
                "{\"name\":\"%s\",\"category\":\"%s\",\"taxCategory\":\"TAXABLE\",\"status\":\"APPROVED\"}".formatted(name, category));
        assertEquals(201, res.status(), res.body());
        return res.id();
    }

    int feedPrice(String customer, String name) {
        List<Integer> prices = get("/api/browse/products?" + HERE + "&q=" + name.replace(" ", "%20"), customer)
                .read("$.content[*].offer.priceCents");
        assertEquals(1, prices.size(), name + " not in the feed");
        return prices.get(0);
    }

    // ------------------------------------------------------------ prices

    @Test
    void pricesFollowAdminSettingsAndPastOrdersKeepTheirs() {
        String admin = adminToken();
        String tag = tag();
        String category = "Snacks " + tag;
        Stocked s = stockedShop(admin, 1000, 50);
        long chips = stock(s.shop(), productIn(admin, "Chips " + tag, category), 1000, 50);
        String customer = customer();

        Res settings = get("/api/admin/settings", admin);
        assertEquals(200, settings.status(), settings.body());
        assertEquals(150, settings.<Integer>read("$.defaultMarkupPercent"));
        assertEquals(499, settings.<Integer>read("$.deliveryFeeCents"));
        assertEquals(499, settings.<Integer>read("$.driverPayCents"));
        assertTrue(get("/api/catalog/categories", customer).<List<String>>read("$").contains(category));

        // A markup for one category.
        Res markup = put("/api/admin/settings/markups/" + category.replace(" ", "%20"), admin, "{\"markupPercent\":200}");
        assertEquals(200, markup.status(), markup.body());
        assertEquals(List.of(200), markup.<List<Integer>>read("$.categoryMarkups[?(@.category == '" + category + "')].markupPercent"));
        assertEquals(2000, feedPrice(customer, "Chips " + tag), "shop price 1000 x 2.0 for this category");

        Res before = order(customer, s.shopId(), item(s.rowId(), 1), item(chips, 1));
        assertEquals(List.of(1500), before.<List<Integer>>read("$.items[?(@.name != 'Chips " + tag + "')].unitPriceCents"),
                "other categories keep the default 1.5x");
        assertEquals(List.of(2000), before.<List<Integer>>read("$.items[?(@.name == 'Chips " + tag + "')].unitPriceCents"));
        assertEquals(499, before.<Integer>read("$.deliveryFeeCents"));
        assertEquals(499, get("/api/admin/orders/" + before.id(), admin).<Integer>read("$.driverPayCents"));

        // New defaults, fee and driver pay.
        Res changed = put("/api/admin/settings", admin, "{\"defaultMarkupPercent\":120,\"deliveryFeeCents\":299,\"driverPayCents\":650}");
        assertEquals(200, changed.status(), changed.body());
        Res after = order(customer, s.shopId(), item(s.rowId(), 1));
        assertEquals(1200, after.<Integer>read("$.items[0].unitPriceCents"));
        assertEquals(299, after.<Integer>read("$.deliveryFeeCents"));
        assertEquals(1499, after.<Integer>read("$.totalCents"));
        assertEquals(650, get("/api/admin/orders/" + after.id(), admin).<Integer>read("$.driverPayCents"));

        Res old = get("/api/orders/" + before.id(), customer);
        assertEquals(499, old.<Integer>read("$.deliveryFeeCents"), "a placed order keeps its fee");
        assertEquals(List.of(1500, 2000), old.<List<Integer>>read("$.items[*].unitPriceCents").stream().sorted().toList(),
                "and its prices");
        assertEquals(499, get("/api/admin/orders/" + before.id(), admin).<Integer>read("$.driverPayCents"), "and its driver pay");

        // Removing the category markup falls back to the default.
        String path = "/api/admin/settings/markups/" + category.replace(" ", "%20");
        assertEquals(204, delete(path, admin).status());
        assertEquals(1200, feedPrice(customer, "Chips " + tag));
        assertEquals(404, delete(path, admin).status());
    }

    @Test
    void settingsAreValidatedAndAdminOnly() {
        String admin = adminToken();
        for (String bad : List.of("{}", "{\"defaultMarkupPercent\":99,\"deliveryFeeCents\":499,\"driverPayCents\":499}",
                "{\"defaultMarkupPercent\":1001,\"deliveryFeeCents\":499,\"driverPayCents\":499}",
                "{\"defaultMarkupPercent\":150,\"deliveryFeeCents\":-1,\"driverPayCents\":499}",
                "{\"defaultMarkupPercent\":150,\"deliveryFeeCents\":499,\"driverPayCents\":10001}")) {
            assertEquals(400, put("/api/admin/settings", admin, bad).status(), bad);
        }
        assertEquals(400, put("/api/admin/settings/markups/Dairy", admin, "{\"markupPercent\":50}").status(),
                "never below the shop's own price");
        assertEquals(400, put("/api/admin/settings/markups/%20", admin, "{\"markupPercent\":150}").status());
        assertEquals(150, get("/api/admin/settings", admin).<Integer>read("$.defaultMarkupPercent"), "nothing changed");

        String shop = shopOwner();
        String customer = customer();
        for (String token : List.of(shop, customer)) {
            assertEquals(403, get("/api/admin/settings", token).status());
            assertEquals(403, put("/api/admin/settings", token, DEFAULT_SETTINGS).status());
            assertEquals(403, put("/api/admin/settings/markups/Dairy", token, "{\"markupPercent\":300}").status());
        }
    }

    // ----------------------------------------------------- opening hours

    @Test
    void customersCanOnlyBuyWhileTheShopIsOpen() {
        String admin = adminToken();
        String tag = tag();
        Stocked s = stockedShop(admin, 1000, 50); // open all week
        long row = stock(s.shop(), productIn(admin, "Tea " + tag, "Drinks"), 400, 10);
        String customer = customer();
        String shopPage = "/api/browse/shops/" + s.shopId() + "/products?" + HERE;

        assertEquals(1, get("/api/browse/products?" + HERE + "&q=" + tag, customer).<List<?>>read("$.content").size());
        assertEquals(7, get("/api/admin/shops/" + s.shopId(), admin).<List<?>>read("$.hours").size());

        // Open every day except today (in the shop's own time zone): closed right now.
        DayOfWeek today = LocalDate.now(ZoneId.of("America/Vancouver")).getDayOfWeek();
        String notToday = "{\"hours\":[" + String.join(",", Arrays.stream(DayOfWeek.values()).filter(d -> d != today)
                .map(d -> "{\"dayOfWeek\":\"" + d + "\",\"opens\":\"00:00\",\"closes\":\"23:59:59\"}").toList()) + "]}";
        Res closed = put("/api/admin/shops/" + s.shopId() + "/hours", admin, notToday);
        assertEquals(200, closed.status(), closed.body());
        assertEquals(6, closed.<List<?>>read("$.hours").size());

        assertEquals(0, get("/api/browse/products?" + HERE + "&q=" + tag, customer).<List<?>>read("$.content").size(),
                "a closed shop's products leave the feed");
        assertEquals(404, get(shopPage, customer).status());
        assertEquals(List.of(), get("/api/browse/products/" + get("/api/shops/me/products?size=100", s.shop())
                .<List<Integer>>read("$.content[?(@.id == " + row + ")].productId").get(0) + "/offers?" + HERE, customer).read("$"));
        Res refused = post("/api/orders", customer, orderJson(s.shopId(), item(row, 1)));
        assertEquals(409, refused.status(), refused.body());
        assertTrue(refused.body().contains("closed"), refused.body());
        assertEquals(10, stockOf(s.shop(), row), "a refused order takes no stock");

        // No hours at all is closed too.
        assertEquals(200, put("/api/admin/shops/" + s.shopId() + "/hours", admin, "{\"hours\":[]}").status());
        assertEquals(409, post("/api/orders", customer, orderJson(s.shopId(), item(row, 1))).status());

        openAllWeek(admin, s.shopId());
        assertEquals(200, get(shopPage, customer).status());
        assertEquals(201, post("/api/orders", customer, orderJson(s.shopId(), item(row, 1))).status());
    }

    @Test
    void hoursAreValidatedAndOnlyAdminSetsThem() {
        String admin = adminToken();
        String owner = shopOwner();
        long shopId = get("/api/shops/me", owner).id();
        String path = "/api/admin/shops/" + shopId + "/hours";

        assertEquals(400, put(path, admin, "{\"hours\":[{\"dayOfWeek\":\"MONDAY\",\"opens\":\"09:00\",\"closes\":\"21:00\"},"
                + "{\"dayOfWeek\":\"MONDAY\",\"opens\":\"10:00\",\"closes\":\"20:00\"}]}").status(), "same day twice");
        assertEquals(400, put(path, admin, "{\"hours\":[{\"dayOfWeek\":\"MONDAY\",\"opens\":\"21:00\",\"closes\":\"09:00\"}]}").status(),
                "closes before opens");
        assertEquals(400, put(path, admin, "{\"hours\":[{\"dayOfWeek\":\"MONDAY\",\"opens\":\"09:00\",\"closes\":\"09:00\"}]}").status());
        assertEquals(400, put(path, admin, "{\"hours\":[{\"dayOfWeek\":\"FUNDAY\",\"opens\":\"09:00\",\"closes\":\"21:00\"}]}").status());
        assertEquals(400, put(path, admin, "{}").status());
        assertEquals(0, get("/api/admin/shops/" + shopId, admin).<List<?>>read("$.hours").size(), "nothing was saved");

        assertEquals(200, put(path, admin, "{\"hours\":[{\"dayOfWeek\":\"SATURDAY\",\"opens\":\"10:00\",\"closes\":\"18:30\"}]}").status());
        Res shop = get("/api/shops/me", owner);
        assertEquals(List.of("SATURDAY"), shop.<List<String>>read("$.hours[*].dayOfWeek"), "the owner sees what admin set");
        assertEquals(List.of("18:30:00"), shop.<List<String>>read("$.hours[*].closes"));

        assertEquals(403, put(path, owner, ALL_WEEK).status(), "the owner cannot set hours");
        assertEquals(404, put("/api/admin/shops/999999999/hours", admin, ALL_WEEK).status());
        assertEquals(404, get("/api/admin/shops/999999999", admin).status());
    }

    // -------------------------------------------------------- order board

    @Test
    void theOrderBoardShowsEverythingAndFilters() {
        Scene s = scene(20);
        long placed = post("/api/orders", s.customer(), orderJsonWith(s.shopId(), UUID.randomUUID().toString(), doorNear(s),
                item(s.rowId(), 1))).id();
        long accepted = acceptedOrder(s, s.customer(), 3);
        String admin = s.admin();

        Res detail = get("/api/admin/orders/" + accepted, admin);
        assertEquals(200, detail.status(), detail.body());
        assertEquals("ACCEPTED", detail.read("$.status"));
        assertEquals("Corner Stop", detail.read("$.shop.name"));
        assertEquals((int) s.shopId(), detail.<Integer>read("$.shop.id"));
        assertEquals("Test Customer", detail.read("$.customer.name"));
        assertNull(detail.read("$.driver"));
        assertEquals(1000, detail.<Integer>read("$.items[0].shopPriceCents"), "admin sees both prices");
        assertEquals(1500, detail.<Integer>read("$.items[0].customerPriceCents"));
        assertEquals(3000, detail.<Integer>read("$.shopPayoutCents"));
        assertEquals(4500 + 499, detail.<Integer>read("$.totalCents"));
        assertEquals("9 Door St", detail.read("$.address.addressLine"));
        assertEquals("Gate code 4411", detail.read("$.note"));
        assertFalse(detail.body().contains("deliveryCode"), "not even admin is sent the delivery code");

        String byShop = "/api/admin/orders?size=100&shopId=" + s.shopId();
        assertEquals(List.of((int) accepted, (int) placed), get(byShop, admin).<List<Integer>>read("$.content[*].id"), "newest first");
        assertEquals(List.of((int) placed), get(byShop + "&status=PLACED", admin).<List<Integer>>read("$.content[*].id"));
        assertEquals(List.of((int) accepted), get(byShop + "&needsDriver=true", admin).<List<Integer>>read("$.content[*].id"));
        assertEquals(2, get("/api/admin/orders?size=100&customerId=" + get("/api/auth/me", s.customer()).id(), admin)
                .<Integer>read("$.page.totalElements"));

        DriverAt driver = driverNear(s);
        post("/api/drivers/me/jobs/" + accepted + "/accept", driver.token(), null);
        assertEquals(0, get(byShop + "&needsDriver=true", admin).<List<?>>read("$.content").size(), "it has a driver now");
        Res withDriver = get("/api/admin/orders/" + accepted, admin);
        assertEquals("Dana Driver", withDriver.read("$.driver.name"));
        assertEquals(List.of((int) accepted), get("/api/admin/orders?driverId=" + driver.userId(), admin).<List<Integer>>read("$.content[*].id"));

        assertEquals(400, get("/api/admin/orders?status=NOPE", admin).status());
        assertEquals(404, get("/api/admin/orders/999999999", admin).status());
        for (String token : List.of(s.shop(), s.customer(), driver.token())) {
            assertEquals(403, get("/api/admin/orders", token).status());
            assertEquals(403, get("/api/admin/orders/" + accepted, token).status());
        }
    }

    // ----------------------------------------------------------- cancel

    @Test
    void adminCancelsAtEachStepAndStockIsHandledRightly() {
        Scene s = scene(10);
        String admin = s.admin();
        long rowB = stock(s.shop(), product(admin, "Other " + UUID.randomUUID()), 500, 10);

        // PLACED: all stock comes back, and the customer and shop see why.
        long placed = post("/api/orders", s.customer(), orderJsonWith(s.shopId(), UUID.randomUUID().toString(), doorNear(s),
                item(s.rowId(), 2))).id();
        assertEquals(8, stockOf(s.shop(), s.rowId()));
        Res cancelled = post("/api/admin/orders/" + placed + "/cancel", admin, "{\"reason\":\"Customer asked by phone\"}");
        assertEquals(200, cancelled.status(), cancelled.body());
        assertEquals("CANCELLED", cancelled.read("$.status"));
        assertEquals(10, stockOf(s.shop(), s.rowId()));
        Res customerSees = get("/api/orders/" + placed, s.customer());
        assertEquals("CANCELLED", customerSees.read("$.status"));
        assertEquals("Customer asked by phone", customerSees.read("$.cancelReason"));
        assertEquals("Customer asked by phone", get("/api/shops/me/orders/" + placed, s.shop()).read("$.cancelReason"));
        assertEquals(409, post("/api/admin/orders/" + placed + "/cancel", admin, "{\"reason\":\"again\"}").status());
        long own = post("/api/orders", s.customer(), orderJsonWith(s.shopId(), UUID.randomUUID().toString(), doorNear(s),
                item(s.rowId(), 1))).id();
        assertNull(post("/api/orders/" + own + "/cancel", s.customer(), null).read("$.cancelReason"),
                "the customer's own cancel has no reason");

        // ACCEPTED with a missing item: the supplied line's stock comes back, the missing one stays at zero.
        Res two = post("/api/orders", s.customer(), orderJsonWith(s.shopId(), UUID.randomUUID().toString(), doorNear(s),
                item(s.rowId(), 2), item(rowB, 3)));
        List<Integer> missing = two.read("$.items[?(@.quantity == 3)].id");
        assertEquals(200, post("/api/shops/me/orders/" + two.id() + "/accept", s.shop(), "{\"unavailableItemIds\":" + missing + "}").status());
        assertEquals(8, stockOf(s.shop(), s.rowId()));
        assertEquals(0, stockOf(s.shop(), rowB));
        assertEquals(200, post("/api/admin/orders/" + two.id() + "/cancel", admin, "{\"reason\":\"Shop called\"}").status());
        assertEquals(10, stockOf(s.shop(), s.rowId()));
        assertEquals(0, stockOf(s.shop(), rowB), "stock is not put back on a product the shop said it does not have");

        // PICKED_UP: the goods have left the shop, so no stock comes back. The driver is free again.
        long road = acceptedOrder(s, s.customer(), 4);
        DriverAt driver = driverNear(s);
        post("/api/drivers/me/jobs/" + road + "/accept", driver.token(), null);
        post("/api/shops/me/orders/" + road + "/ready", s.shop(), null);
        post("/api/drivers/me/delivery/picked-up", driver.token(), null);
        assertEquals(6, stockOf(s.shop(), s.rowId()));
        assertEquals(200, post("/api/admin/orders/" + road + "/cancel", admin, "{\"reason\":\"Driver had an accident\"}").status());
        assertEquals(6, stockOf(s.shop(), s.rowId()));
        assertEquals(404, get("/api/drivers/me/delivery", driver.token()).status());
        assertNull(get("/api/orders/" + road + "/tracking", s.customer()).read("$.driver"), "tracking stops");
        String nextCustomer = customer();
        long next = acceptedOrder(s, nextCustomer, 1);
        assertEquals(200, post("/api/drivers/me/jobs/" + next + "/accept", driver.token(), null).status(),
                "the driver can take a new job");

        // Finished orders cannot be cancelled, and a reason is required.
        post("/api/shops/me/orders/" + next + "/ready", s.shop(), null);
        post("/api/drivers/me/delivery/picked-up", driver.token(), null);
        String code = get("/api/orders/" + next, nextCustomer).read("$.deliveryCode");
        assertEquals(200, post("/api/drivers/me/delivery/delivered", driver.token(), "{\"code\":\"" + code + "\"}").status());
        assertEquals("DELIVERED", get("/api/admin/orders/" + next, admin).read("$.status"));
        assertEquals(409, post("/api/admin/orders/" + next + "/cancel", admin, "{\"reason\":\"too late\"}").status());
        assertEquals(400, post("/api/admin/orders/" + road + "/cancel", admin, "{\"reason\":\"  \"}").status());
        assertEquals(400, post("/api/admin/orders/" + road + "/cancel", admin, "{}").status());
        assertEquals(404, post("/api/admin/orders/999999999/cancel", admin, "{\"reason\":\"x\"}").status());
        assertNull(get("/api/orders/" + next, nextCustomer).read("$.cancelReason"), "a delivered order has no cancel reason");
    }

    // ------------------------------------------------------ assign drivers

    @Test
    void adminAssignsReassignsAndRemovesDrivers() {
        Scene s = scene(20);
        String admin = s.admin();
        long orderId = acceptedOrder(s, s.customer(), 1);
        DriverAt first = driverNear(s);
        DriverAt second = driverNear(s);
        post("/api/drivers/me/offline", second.token(), null); // offline drivers can be assigned by admin

        String assign = "/api/admin/orders/" + orderId + "/assign";
        Res assigned = post(assign, admin, "{\"driverId\":" + first.userId() + "}");
        assertEquals(200, assigned.status(), assigned.body());
        assertEquals((int) first.userId(), assigned.<Integer>read("$.driver.id"));
        assertEquals((int) orderId, get("/api/drivers/me/delivery", first.token()).<Integer>read("$.orderId"));
        assertTrue(get("/api/orders/" + orderId, s.customer()).<Boolean>read("$.driverAssigned"));
        assertEquals(200, post(assign, admin, "{\"driverId\":" + first.userId() + "}").status(), "assigning the same driver again is fine");

        // Reassign: the order moves to the second driver.
        assertEquals(200, post(assign, admin, "{\"driverId\":" + second.userId() + "}").status());
        assertEquals(404, get("/api/drivers/me/delivery", first.token()).status());
        assertEquals((int) orderId, get("/api/drivers/me/delivery", second.token()).<Integer>read("$.orderId"));

        // A busy driver cannot take a second order.
        long other = acceptedOrder(s, customer(), 1);
        Res busy = post("/api/admin/orders/" + other + "/assign", admin, "{\"driverId\":" + second.userId() + "}");
        assertEquals(409, busy.status(), busy.body());

        // Only approved drivers.
        Res pendingSignup = post("/api/auth/signup", null,
                "{\"email\":\"%s\",\"password\":\"%s\",\"name\":\"New Driver\",\"role\":\"DRIVER\"}".formatted(newEmail(), PASSWORD));
        String pendingToken = pendingSignup.read("$.token");
        put("/api/drivers/me", pendingToken, "{\"phone\":\"+1 604 555 0150\"}");
        long pendingId = pendingSignup.<Number>read("$.user.id").longValue();
        assertEquals(409, post("/api/admin/orders/" + other + "/assign", admin, "{\"driverId\":" + pendingId + "}").status());
        assertEquals(404, post("/api/admin/orders/" + other + "/assign", admin, "{\"driverId\":999999999}").status());
        assertEquals(404, post("/api/admin/orders/" + other + "/assign", admin,
                "{\"driverId\":" + get("/api/auth/me", s.customer()).id() + "}").status(), "a customer is not a driver");

        // Unassign: back to the pool, where any driver can take it.
        assertEquals(200, post("/api/admin/orders/" + orderId + "/unassign", admin, null).status());
        assertEquals(404, get("/api/drivers/me/delivery", second.token()).status());
        assertTrue(get("/api/drivers/me/jobs", first.token()).<List<Integer>>read("$[*].orderId").contains((int) orderId));
        assertEquals(409, post("/api/admin/orders/" + orderId + "/unassign", admin, null).status(), "no driver to remove");

        // After pickup, neither works.
        post("/api/drivers/me/jobs/" + orderId + "/accept", first.token(), null);
        post("/api/shops/me/orders/" + orderId + "/ready", s.shop(), null);
        post("/api/drivers/me/delivery/picked-up", first.token(), null);
        assertEquals(409, post("/api/admin/orders/" + orderId + "/unassign", admin, null).status());
        assertEquals(409, post(assign, admin, "{\"driverId\":" + second.userId() + "}").status());

        // An order the shop has not accepted cannot be assigned.
        long placed = post("/api/orders", s.customer(), orderJsonWith(s.shopId(), UUID.randomUUID().toString(), doorNear(s),
                item(s.rowId(), 1))).id();
        assertEquals(409, post("/api/admin/orders/" + placed + "/assign", admin, "{\"driverId\":" + second.userId() + "}").status());
        assertEquals(403, post(assign, first.token(), "{\"driverId\":" + first.userId() + "}").status());
    }

    // --------------------------------------------------------- accounts

    @Test
    void aSuspendedAccountIsLockedOutAtOnce() {
        String admin = adminToken();
        String email = newEmail();
        Res signup = post("/api/auth/signup", null,
                "{\"email\":\"%s\",\"password\":\"%s\",\"name\":\"Sam Spammer\",\"role\":\"CUSTOMER\"}".formatted(email, PASSWORD));
        String token = signup.read("$.token");
        long userId = signup.<Number>read("$.user.id").longValue();
        assertEquals(200, get("/api/orders", token).status());

        Res suspended = put("/api/admin/users/" + userId + "/disabled", admin, "{\"disabled\":true}");
        assertEquals(200, suspended.status(), suspended.body());
        assertTrue(suspended.<Boolean>read("$.disabled"));
        assertEquals(401, get("/api/orders", token).status(), "the token already held stops working");
        assertEquals(401, get("/api/auth/me", token).status());
        Res login = post("/api/auth/login", null, loginJson(email, PASSWORD));
        assertEquals(403, login.status(), login.body());
        assertEquals(401, post("/api/auth/login", null, loginJson(email, "wrong-password")).status(),
                "a wrong password says nothing about the suspension");

        assertEquals(List.of(true), get("/api/admin/users?role=CUSTOMER&q=sam%20spammer&size=100", admin)
                .<List<Boolean>>read("$.content[?(@.id == " + userId + ")].disabled"));

        assertFalse(put("/api/admin/users/" + userId + "/disabled", admin, "{\"disabled\":false}").<Boolean>read("$.disabled"));
        assertEquals(200, get("/api/orders", token).status(), "restored: the same token works again");
        assertEquals(200, post("/api/auth/login", null, loginJson(email, PASSWORD)).status());

        long adminId = get("/api/auth/me", admin).id();
        assertEquals(400, put("/api/admin/users/" + adminId + "/disabled", admin, "{\"disabled\":true}").status(),
                "admin cannot lock themself or another admin out");
        assertEquals(200, get("/api/admin/stats", admin).status());
        assertEquals(404, put("/api/admin/users/999999999/disabled", admin, "{\"disabled\":true}").status());
        assertEquals(400, put("/api/admin/users/" + userId + "/disabled", admin, "{}").status());
        assertEquals(403, put("/api/admin/users/" + userId + "/disabled", token, "{\"disabled\":false}").status());
    }

    @Test
    void theUserListSearchesAndFilters() {
        String admin = adminToken();
        String tag = tag();
        post("/api/auth/signup", null, "{\"email\":\"%s@x.test\",\"password\":\"%s\",\"name\":\"Rider %s\",\"role\":\"DRIVER\"}".formatted(tag, PASSWORD, tag));
        Res found = get("/api/admin/users?q=" + tag, admin);
        assertEquals(200, found.status(), found.body());
        assertEquals(List.of("DRIVER"), found.<List<String>>read("$.content[*].role"));
        assertEquals(0, get("/api/admin/users?role=SHOP&q=" + tag, admin).<List<?>>read("$.content").size());
        assertEquals(0, get("/api/admin/users?q=%25", admin).<List<?>>read("$.content").size(), "% is plain text, not a wildcard");
        assertFalse(found.body().contains("passwordHash") || found.body().contains("$2a$"), "never the password hash");
        assertEquals(400, get("/api/admin/users?role=BOSS", admin).status());
    }

    // ------------------------------------------------- dashboard, health

    @Test
    void theDashboardCountsWhatNeedsAttention() {
        Scene s = scene(10);
        acceptedOrder(s, s.customer(), 1);
        Res stats = get("/api/admin/stats", s.admin());
        assertEquals(200, stats.status(), stats.body());
        for (String field : List.of("waitingForShop", "needsDriver", "onTheRoad", "placedLast24h", "deliveredLast24h",
                "shopsPending", "driversPending", "driversOnline", "productRequestsPending")) {
            assertTrue(stats.<Number>read("$." + field).longValue() >= 0, field);
        }
        assertTrue(stats.<Number>read("$.needsDriver").longValue() >= 1);
        assertTrue(stats.<Number>read("$.placedLast24h").longValue() >= 1);
        assertEquals(403, get("/api/admin/stats", s.shop()).status());
    }

    @Test
    void theHealthCheckIsOpenAndSaysNothingElse() {
        Res health = get("/actuator/health", null);
        assertEquals(200, health.status(), health.body());
        assertEquals("UP", health.read("$.status"));
        assertFalse(health.body().contains("db") || health.body().contains("postgres"), "no details: " + health.body());
        String admin = adminToken();
        for (String other : List.of("/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/metrics")) {
            assertEquals(401, get(other, null).status(), other);
            assertEquals(404, get(other, admin).status(), other + " is not exposed, even to admin");
        }
    }
}
