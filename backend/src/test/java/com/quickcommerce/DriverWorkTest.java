package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quickcommerce.common.Geo;
import com.quickcommerce.common.Settings;
import com.quickcommerce.work.DriverWork;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Engaged-time pay, pay statements, violations, admin alerts, and "Did you receive your order?". */
class DriverWorkTest extends ApiTestBase {

    @Autowired
    DataSource dataSource;
    @Autowired
    DriverWork work;
    @Autowired
    Settings settings;

    JdbcClient db() {
        return JdbcClient.create(dataSource);
    }

    /** Takes the order from acceptance to the door; returns the delivered view. */
    Res deliverAll(Scene s, String customer, long orderId, DriverAt driver) {
        assertEquals(200, post("/api/drivers/me/jobs/" + orderId + "/accept", driver.token(), null).status());
        post("/api/shops/me/orders/" + orderId + "/ready", s.shop(), null);
        assertEquals(200, post("/api/drivers/me/delivery/picked-up", driver.token(), null).status());
        String code = get("/api/orders/" + orderId, customer).read("$.deliveryCode");
        Res delivered = post("/api/drivers/me/delivery/delivered", driver.token(), "{\"code\":\"" + code + "\"}");
        assertEquals(200, delivered.status(), delivered.body());
        return delivered;
    }

    @Test
    void payIsEngagedTimePlusKmFromAcceptanceToTheDoor() {
        Scene s = scene(20);
        try {
            // Hourly 0, 100 cents per km: the pay is then exactly the paid km, which we can check.
            put("/api/admin/settings", s.admin(), "{\"driverHourlyCents\":0,\"driverPerKmCents\":100}");
            long orderId = acceptedOrder(s, s.customer(), 2);
            DriverAt driver = driverNear(s);
            deliverAll(s, s.customer(), orderId, driver);

            double toShop = Geo.distanceKm(s.lat() + 0.004, s.lng() - 0.004, s.lat(), s.lng());
            double toDoor = Geo.distanceKm(s.lat(), s.lng(), s.lat() + 0.01, s.lng() - 0.01);
            long expected = Math.round((toShop + toDoor) * 1.3 * 100);
            int paid = get("/api/admin/orders/" + orderId, s.admin()).read("$.driverPayCents");
            assertEquals(expected, paid, 1, "driver to shop to door, x1.3 for roads, at 1.00 a km");

            // Hourly pay counts from acceptance: $36 an hour is 1 cent a second.
            put("/api/admin/settings", s.admin(), "{\"driverHourlyCents\":3600,\"driverPerKmCents\":0}");
            long timed = acceptedOrder(s, s.customer(), 1);
            DriverAt second = driverNear(s);
            post("/api/drivers/me/jobs/" + timed + "/accept", second.token(), null);
            db().sql("update driver_engagements set accepted_at = accepted_at - interval '10 minutes' where order_id = :o")
                    .param("o", timed).update(); // as if accepted 10 minutes ago
            post("/api/shops/me/orders/" + timed + "/ready", s.shop(), null);
            post("/api/drivers/me/delivery/picked-up", second.token(), null);
            post("/api/drivers/me/delivery/delivered", second.token(),
                    "{\"code\":\"" + get("/api/orders/" + timed, s.customer()).read("$.deliveryCode") + "\"}");
            int timedPay = get("/api/admin/orders/" + timed, s.admin()).read("$.driverPayCents");
            assertTrue(timedPay >= 600 && timedPay < 620, "about 600 seconds of engaged time, got " + timedPay);

            // The statement adds it all up for the current two-week period.
            Res statement = get("/api/drivers/me/pay-statements?count=2", second.token());
            assertEquals(200, statement.status(), statement.body());
            assertEquals(1, statement.<Integer>read("$[0].deliveries"));
            assertEquals(timedPay, statement.<Integer>read("$[0].earningsCents"));
            assertTrue(statement.<Integer>read("$[0].engagedMinutes") >= 10);
            assertEquals(14, java.time.temporal.ChronoUnit.DAYS.between(
                    LocalDate.parse(statement.read("$[0].period.start")), LocalDate.parse(statement.read("$[0].period.payDay"))));
            assertEquals("TUESDAY", LocalDate.parse(statement.read("$[0].period.payDay")).getDayOfWeek().toString());
            assertEquals(0, statement.<Integer>read("$[1].deliveries"), "the period before is empty");
        } finally {
            put("/api/admin/settings", s.admin(), "{\"driverHourlyCents\":2199,\"driverPerKmCents\":35}");
        }
    }

    @Test
    void aDriverWhoGivesTheJobBackIsPaidForTheTimeHeldAndGetsAViolation() {
        Scene s = scene(20);
        long orderId = acceptedOrder(s, s.customer(), 1);
        DriverAt driver = driverNear(s);
        post("/api/drivers/me/jobs/" + orderId + "/accept", driver.token(), null);
        assertEquals(409, post("/api/drivers/me/offline", driver.token(), null).status(), "not while holding an order");
        assertEquals(204, post("/api/drivers/me/delivery/release", driver.token(), null).status());
        assertEquals(200, post("/api/drivers/me/offline", driver.token(), null).status());

        Res record = get("/api/drivers/me/violations", driver.token());
        assertEquals(1, record.<Integer>read("$.today"));
        assertEquals(List.of("JOB_RELEASED"), record.<List<String>>read("$.recent[*].type"));
        assertEquals(3, record.<Integer>read("$.dailyLimit"));
        assertEquals(6, record.<Integer>read("$.monthlyLimit"));
        assertTrue(get("/api/drivers/me/pay-statements?count=1", driver.token()).<Integer>read("$[0].earningsCents") > 0,
                "engaged time counts until the job was given back");
    }

    @Test
    void lateDeliveriesLostLocationAndCodeGuessingAreViolations() {
        Scene s = scene(30);
        DriverAt driver = driverNear(s);

        // Late: picked up 25 minutes before delivering (limit 20).
        long late = acceptedOrder(s, s.customer(), 1);
        post("/api/drivers/me/jobs/" + late + "/accept", driver.token(), null);
        post("/api/shops/me/orders/" + late + "/ready", s.shop(), null);
        post("/api/drivers/me/delivery/picked-up", driver.token(), null);
        db().sql("update driver_engagements set picked_up_at = now() - interval '25 minutes' where order_id = :o")
                .param("o", late).update();
        post("/api/drivers/me/location", driver.token(), position(s.lat() + 0.01, s.lng() - 0.01)); // at the door
        post("/api/drivers/me/delivery/delivered", driver.token(),
                "{\"code\":\"" + get("/api/orders/" + late, s.customer()).read("$.deliveryCode") + "\"}");
        assertEquals(List.of("LATE_DELIVERY"), get("/api/drivers/me/violations", driver.token()).<List<String>>read("$.recent[*].type"));
        assertNull(get("/api/orders/" + late, s.customer()).read("$.receiptStatus"), "delivered at the door: nothing to ask");

        // Location lost: no ping for 12 minutes while holding an order. Flagged once, however often the check runs.
        long lost = acceptedOrder(s, s.customer(), 1);
        post("/api/drivers/me/jobs/" + lost + "/accept", driver.token(), null);
        db().sql("update driver_engagements set accepted_at = now() - interval '12 minutes' where order_id = :o").param("o", lost).update();
        db().sql("update drivers set located_at = now() - interval '12 minutes' where user_id = :d").param("d", driver.userId()).update();
        work.flagLostLocations();
        work.flagLostLocations();
        Res record = get("/api/drivers/me/violations", driver.token());
        assertEquals(1, record.<List<String>>read("$.recent[?(@.type == 'LOCATION_LOST')]").size());

        // The phone comes back; the order is delivered; because the location was lost, the customer is asked.
        post("/api/drivers/me/location", driver.token(), position(s.lat() + 0.01, s.lng() - 0.01));
        post("/api/shops/me/orders/" + lost + "/ready", s.shop(), null);
        post("/api/drivers/me/delivery/picked-up", driver.token(), null);
        post("/api/drivers/me/delivery/delivered", driver.token(),
                "{\"code\":\"" + get("/api/orders/" + lost, s.customer()).read("$.deliveryCode") + "\"}");
        assertEquals("ASKED", get("/api/orders/" + lost, s.customer()).read("$.receiptStatus"));
        assertEquals("RECEIVED", post("/api/orders/" + lost + "/receipt", s.customer(), "{\"received\":true}").read("$.receiptStatus"));
        assertEquals(409, post("/api/orders/" + lost + "/receipt", s.customer(), "{\"received\":false}").status(), "answered once");

        // Code guessing: using up the tries is one violation.
        long guessed = acceptedOrder(s, s.customer(), 1);
        DriverAt guesser = driverNear(s);
        post("/api/drivers/me/jobs/" + guessed + "/accept", guesser.token(), null);
        post("/api/shops/me/orders/" + guessed + "/ready", s.shop(), null);
        post("/api/drivers/me/delivery/picked-up", guesser.token(), null);
        String code = get("/api/orders/" + guessed, s.customer()).read("$.deliveryCode");
        String wrong = code.equals("0000") ? "1111" : "0000";
        for (int i = 0; i < 8; i++) {
            post("/api/drivers/me/delivery/delivered", guesser.token(), "{\"code\":\"" + wrong + "\"}");
        }
        assertEquals(List.of("CODE_TRIES_USED"), get("/api/drivers/me/violations", guesser.token()).<List<String>>read("$.recent[*].type"));
    }

    @Test
    void deliveredFarFromTheDoorTheCustomerIsAskedAndANoAlertsAdmin() {
        Scene s = scene(10);
        long orderId = acceptedOrder(s, s.customer(), 1);
        DriverAt driver = driverNear(s);
        post("/api/drivers/me/jobs/" + orderId + "/accept", driver.token(), null);
        post("/api/shops/me/orders/" + orderId + "/ready", s.shop(), null);
        post("/api/drivers/me/delivery/picked-up", driver.token(), null);
        // Marks it delivered about 1.3 km from the address.
        post("/api/drivers/me/delivery/delivered", driver.token(),
                "{\"code\":\"" + get("/api/orders/" + orderId, s.customer()).read("$.deliveryCode") + "\"}");
        assertEquals("ASKED", get("/api/orders/" + orderId, s.customer()).read("$.receiptStatus"));

        Res no = post("/api/orders/" + orderId + "/receipt", s.customer(), "{\"received\":false}");
        assertEquals(200, no.status(), no.body());
        assertEquals("NOT_RECEIVED", no.read("$.receiptStatus"));
        assertEquals(List.of("NOT_RECEIVED"), get("/api/drivers/me/violations", driver.token()).<List<String>>read("$.recent[*].type"));
        Res alerts = get("/api/admin/driver-alerts", s.admin());
        assertEquals(List.of("NOT_RECEIVED"), alerts.<List<String>>read("$[?(@.driverId == " + driver.userId() + ")].kind"));
        assertEquals(List.of((int) orderId), alerts.<List<Integer>>read("$[?(@.driverId == " + driver.userId() + ")].orderId"));
        assertEquals(403, get("/api/admin/driver-alerts", driver.token()).status());

        // Customers can also report a missing order within 24 h without being asked.
        long another = acceptedOrder(s, s.customer(), 1);
        DriverAt other = driverNear(s);
        post("/api/drivers/me/jobs/" + another + "/accept", other.token(), null);
        post("/api/shops/me/orders/" + another + "/ready", s.shop(), null);
        post("/api/drivers/me/delivery/picked-up", other.token(), null);
        post("/api/drivers/me/location", other.token(), position(s.lat() + 0.01, s.lng() - 0.01));
        post("/api/drivers/me/delivery/delivered", other.token(),
                "{\"code\":\"" + get("/api/orders/" + another, s.customer()).read("$.deliveryCode") + "\"}");
        assertNull(get("/api/orders/" + another, s.customer()).read("$.receiptStatus"));
        assertEquals(200, post("/api/orders/" + another + "/receipt", s.customer(), "{\"received\":false}").status());
        long placed = post("/api/orders", s.customer(), orderJsonWith(s.shopId(), java.util.UUID.randomUUID().toString(),
                doorNear(s), item(s.rowId(), 1))).id();
        assertEquals(409, post("/api/orders/" + placed + "/receipt", s.customer(), "{\"received\":false}").status(), "not delivered");
        assertEquals(404, post("/api/orders/" + another + "/receipt", customer(), "{\"received\":false}").status());
    }

    @Test
    void theDailyLimitWarnsAdminAndTheMonthlyLimitAsksForABanDecision() {
        Scene s = scene(10);
        DriverAt driver = driverNear(s);
        long id = driver.userId();
        Settings.Values limits = settings.current();

        for (int i = 0; i < 2; i++) {
            work.recordViolation(id, null, DriverWork.ViolationType.LATE_DELIVERY, "test", limits);
        }
        assertEquals(0, alertsFor(s, id).size(), "2 of 3: no alert yet");
        work.recordViolation(id, null, DriverWork.ViolationType.LATE_DELIVERY, "test", limits);
        assertEquals(List.of("DAILY_LIMIT"), alertsFor(s, id));
        assertEquals(time.today().toString(),
                get("/api/admin/driver-alerts", s.admin()).<List<String>>read("$[?(@.driverId == " + id + ")].day").get(0));

        // 3 more, at the same moment: still one daily alert, and exactly one ban review at 6.
        parallel(3, i -> {
            work.recordViolation(id, null, DriverWork.ViolationType.LOCATION_LOST, "test", limits);
            return null;
        });
        assertEquals(List.of("BAN_REVIEW", "DAILY_LIMIT"), alertsFor(s, id).stream().sorted().toList());

        // Admin dismisses the warning and suspends the driver on the ban review.
        long daily = alertId(s, id, "DAILY_LIMIT");
        long ban = alertId(s, id, "BAN_REVIEW");
        assertEquals("DISMISSED", post("/api/admin/driver-alerts/" + daily + "/resolve", s.admin(), "{\"action\":\"DISMISS\"}").read("$.resolution"));
        Res suspended = post("/api/admin/driver-alerts/" + ban + "/resolve", s.admin(), "{\"action\":\"SUSPEND\"}");
        assertEquals(200, suspended.status(), suspended.body());
        assertEquals("SUSPENDED", suspended.read("$.resolution"));
        assertEquals(409, post("/api/admin/driver-alerts/" + ban + "/resolve", s.admin(), "{\"action\":\"DISMISS\"}").status());
        assertEquals(404, post("/api/admin/driver-alerts/999999999/resolve", s.admin(), "{\"action\":\"DISMISS\"}").status());
        Res me = get("/api/drivers/me", driver.token());
        assertEquals("SUSPENDED", me.read("$.status"));
        assertEquals(false, me.<Boolean>read("$.online"));
        assertEquals(0, alertsFor(s, id).size(), "nothing left open for this driver");
        assertEquals(6, get("/api/admin/drivers/" + id + "/violations", s.admin()).<Integer>read("$.last30Days"));
    }

    List<String> alertsFor(Scene s, long driverId) {
        return get("/api/admin/driver-alerts?open=true", s.admin()).read("$[?(@.driverId == " + driverId + ")].kind");
    }

    long alertId(Scene s, long driverId, String kind) {
        List<Integer> ids = get("/api/admin/driver-alerts?open=true", s.admin())
                .read("$[?(@.driverId == " + driverId + " && @.kind == '" + kind + "')].id");
        return ids.get(0);
    }

    @Test
    void adminSeesEachPeriodsPayAndMarksAFinishedPeriodPaid() {
        Scene s = scene(10);
        long orderId = acceptedOrder(s, s.customer(), 1);
        DriverAt driver = driverNear(s);
        deliverAll(s, s.customer(), orderId, driver);

        Res current = get("/api/drivers/me/pay-statements?count=1", driver.token());
        String start = current.read("$[0].period.start");
        Res period = get("/api/admin/payouts?periodStart=" + start, s.admin());
        assertEquals(200, period.status(), period.body());
        assertEquals(1, period.<List<?>>read("$.drivers[?(@.driverId == " + driver.userId() + ")]").size());
        assertEquals(409, post("/api/admin/payouts/" + driver.userId() + "/" + start + "/paid", s.admin(), null).status(),
                "the period has not ended");
        assertEquals(400, get("/api/admin/payouts?periodStart=" + LocalDate.parse(start).plusDays(1), s.admin()).status(),
                "not a period start");

        // Move this delivery into the previous period, which has ended.
        String previous = LocalDate.parse(start).minusDays(14).toString();
        db().sql("update driver_engagements set ended_at = ended_at - interval '14 days' where order_id = :o").param("o", orderId).update();
        db().sql("update orders set delivered_at = delivered_at - interval '14 days' where id = :o").param("o", orderId).update();
        Res paid = post("/api/admin/payouts/" + driver.userId() + "/" + previous + "/paid", s.admin(), null);
        assertEquals(200, paid.status(), paid.body());
        assertNotNull(paid.read("$.statement.paidAt"));
        assertEquals(409, post("/api/admin/payouts/" + driver.userId() + "/" + previous + "/paid", s.admin(), null).status(),
                "paid once");
        assertNotNull(get("/api/drivers/me/pay-statements?count=2", driver.token()).read("$[1].paidAt"),
                "the driver sees it as paid");
        assertEquals(403, get("/api/admin/payouts", driver.token()).status());
    }

    @Test
    void theMetricsAddUp() {
        assertEquals(Instant.parse("2026-11-24T07:00:00Z"), time.dayStart(LocalDate.of(2026, 11, 24)),
                "Vancouver midnight stays aligned with PostgreSQL after the November time zone change");
        Scene s = scene(10);
        long orderId = acceptedOrder(s, s.customer(), 2);
        deliverAll(s, s.customer(), orderId, driverNear(s));

        String today = time.today().toString();
        Res metrics = get("/api/admin/metrics?from=" + today + "&to=" + today, s.admin());
        assertEquals(200, metrics.status(), metrics.body());
        assertEquals(1, metrics.<List<?>>read("$.days").size());
        assertTrue(metrics.<Integer>read("$.days[0].ordersDelivered") >= 1);
        long sales = metrics.<Number>read("$.days[0].salesCents").longValue();
        long payout = metrics.<Number>read("$.days[0].shopPayoutCents").longValue();
        long fees = metrics.<Number>read("$.days[0].feesCents").longValue();
        long pay = metrics.<Number>read("$.days[0].driverPayCents").longValue();
        assertEquals(sales - payout, metrics.<Number>read("$.days[0].itemMarginCents").longValue());
        assertEquals(sales - payout + fees - pay, metrics.<Number>read("$.days[0].netCents").longValue());
        assertEquals(sales, metrics.<Number>read("$.totals.salesCents").longValue(), "totals of one day are that day");

        assertEquals(30, get("/api/admin/metrics", s.admin()).<List<?>>read("$.days").size(), "default: the last 30 days");
        assertEquals(400, get("/api/admin/metrics?from=2026-01-01&to=2025-01-01", s.admin()).status());
        assertEquals(400, get("/api/admin/metrics?from=2024-01-01&to=2026-01-01", s.admin()).status(), "over 366 days");
        assertEquals(403, get("/api/admin/metrics", s.shop()).status());
        assertTrue(get("/api/admin/stats", s.admin()).<Number>read("$.driverAlertsOpen").longValue() >= 0);
    }
}
