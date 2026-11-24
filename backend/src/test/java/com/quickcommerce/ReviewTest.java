package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Star reviews after delivery, and the ratings customers see while browsing. */
class ReviewTest extends ApiTestBase {

    /** A delivered order in its own area: [orderId, driver user id]; the driver's token is returned through the holder. */
    record Delivered(Scene scene, long orderId, DriverAt driver, long productId) {}

    Delivered delivered() {
        Scene s = scene(20);
        long orderId = acceptedOrder(s, s.customer(), 2);
        DriverAt driver = driverNear(s);
        post("/api/drivers/me/jobs/" + orderId + "/accept", driver.token(), null);
        post("/api/shops/me/orders/" + orderId + "/ready", s.shop(), null);
        post("/api/drivers/me/delivery/picked-up", driver.token(), null);
        post("/api/drivers/me/location", driver.token(), position(s.lat() + 0.01, s.lng() - 0.01));
        post("/api/drivers/me/delivery/delivered", driver.token(),
                "{\"code\":\"" + get("/api/orders/" + orderId, s.customer()).read("$.deliveryCode") + "\"}");
        long productId = get("/api/orders/" + orderId, s.customer()).<Integer>read("$.items[0].productId");
        return new Delivered(s, orderId, driver, productId);
    }

    @Test
    void customersAndRidersReviewOnceAndCustomersSeeTheAverages() {
        Delivered d = delivered();
        Scene s = d.scene();
        String path = "/api/orders/" + d.orderId() + "/reviews";

        assertEquals(0, get(path, s.customer()).<List<?>>read("$").size(), "not reviewed yet");
        Res review = post(path, s.customer(), "{\"shopStars\":5,\"driverStars\":4,\"products\":[{\"productId\":%d,\"stars\":3}],\"comment\":\" Quick! \"}"
                .formatted(d.productId()));
        assertEquals(201, review.status(), review.body());
        assertEquals(3, review.<List<?>>read("$").size());
        assertEquals(List.of("Quick!"), review.<List<String>>read("$[?(@.subjectType == 'SHOP')].comment"));
        assertEquals(409, post(path, s.customer(), "{\"shopStars\":1}").status(), "once per order");

        Res rider = post("/api/drivers/me/deliveries/" + d.orderId() + "/reviews", d.driver().token(),
                "{\"shopStars\":3,\"customerStars\":5}");
        assertEquals(201, rider.status(), rider.body());

        // The shop now averages 4.0 from 2 reviews (5 from the customer, 3 from the rider); the product 3.0 from 1.
        Res shop = get("/api/admin/reviews?subjectType=SHOP&subjectId=" + s.shopId(), s.admin());
        assertEquals(4.0, shop.<Double>read("$.rating.average"));
        assertEquals(2, shop.<Integer>read("$.rating.count"));
        String near = "lat=" + (s.lat() + 0.01) + "&lng=" + (s.lng() - 0.01);
        Res feed = get("/api/browse/products?" + near, customer());
        List<Double> productRatings = feed.read("$.content[?(@.productId == " + d.productId() + ")].rating.average");
        assertEquals(List.of(3.0), productRatings);
        assertEquals(List.of(4.0), feed.<List<Double>>read("$.content[?(@.productId == " + d.productId() + ")].offer.shopRating.average"),
                "shown next to the distance, never with the name");
        assertEquals(4.0, get("/api/browse/products/" + d.productId() + "/offers?" + near, customer()).<Double>read("$[0].shopRating.average"));

        // Admin removes the rider's review; the average follows.
        List<Integer> riderReview = shop.read("$.reviews[?(@.stars == 3)].id");
        assertEquals(204, delete("/api/admin/reviews/" + riderReview.get(0), s.admin()).status());
        assertEquals(5.0, get("/api/admin/reviews?subjectType=SHOP&subjectId=" + s.shopId(), s.admin()).<Double>read("$.rating.average"));
        assertEquals(404, delete("/api/admin/reviews/" + riderReview.get(0), s.admin()).status());
        assertEquals(4.0, get("/api/admin/reviews?subjectType=DRIVER&subjectId=" + d.driver().userId(), s.admin()).<Double>read("$.rating.average"));
        assertEquals(5.0, get("/api/admin/reviews?subjectType=CUSTOMER&subjectId=" + get("/api/auth/me", s.customer()).id(), s.admin())
                .<Double>read("$.rating.average"));
    }

    @Test
    void reviewsAreCheckedAndOnlyForTheOrdersOwnPeople() {
        Delivered d = delivered();
        Scene s = d.scene();
        String path = "/api/orders/" + d.orderId() + "/reviews";
        for (String bad : List.of("{}", "{\"shopStars\":0}", "{\"shopStars\":6}", "{\"products\":[{\"productId\":1,\"stars\":9}]}")) {
            assertEquals(400, post(path, s.customer(), bad).status(), bad);
        }
        assertEquals(400, post(path, s.customer(), "{\"products\":[{\"productId\":999999999,\"stars\":4}]}").status(),
                "only products that were delivered");
        assertEquals(400, post(path, s.customer(), "{\"products\":[{\"productId\":%d,\"stars\":4},{\"productId\":%d,\"stars\":5}]}"
                .formatted(d.productId(), d.productId())).status(), "a product once");
        assertEquals(404, post(path, customer(), "{\"shopStars\":5}").status(), "not their order");
        assertEquals(404, post("/api/drivers/me/deliveries/" + d.orderId() + "/reviews", driverNear(s).token(), "{\"shopStars\":5}").status(),
                "not their delivery");
        assertEquals(403, post(path, d.driver().token(), "{\"shopStars\":5}").status());

        long placed = post("/api/orders", s.customer(), orderJsonWith(s.shopId(), UUID.randomUUID().toString(), doorNear(s),
                item(s.rowId(), 1))).id();
        assertEquals(409, post("/api/orders/" + placed + "/reviews", s.customer(), "{\"shopStars\":5}").status(), "not delivered yet");
        assertEquals(403, get("/api/admin/reviews?subjectType=SHOP&subjectId=" + s.shopId(), s.customer()).status());
        Res feed = get("/api/browse/products?lat=" + (s.lat() + 0.01) + "&lng=" + (s.lng() - 0.01), customer());
        assertEquals(0, feed.<Integer>read("$.content[0].rating.count"), "no reviews yet");
        assertNull(feed.read("$.content[0].rating.average"), "so no average");
    }

    @Test
    void tenReviewsAtOnceForOneOrderSaveOne() {
        Delivered d = delivered();
        String path = "/api/orders/" + d.orderId() + "/reviews";
        List<Res> results = parallel(10, i -> post(path, d.scene().customer(),
                i % 2 == 0 ? "{\"shopStars\":5}" : "{\"driverStars\":4}"));
        assertEquals(1, count(results, 201), ConcurrencyTest.statuses(results));
        assertEquals(9, count(results, 409));
        assertEquals(1, get(path, d.scene().customer()).<List<?>>read("$").size());
    }
}
