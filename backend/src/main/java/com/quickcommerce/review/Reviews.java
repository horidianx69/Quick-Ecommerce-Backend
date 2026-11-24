package com.quickcommerce.review;

import com.quickcommerce.common.Rating;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Star reviews (1 to 5) after a delivered order:
 *  - the customer rates the shop, the rider, and each product they received;
 *  - the rider rates the shop (how smooth the pickup was) and the customer.
 * One submission per person per order, within 7 days of delivery. Averages are
 * kept in rating_totals so feeds can show them without counting reviews.
 */
@Service
public class Reviews {

    public enum Subject { SHOP, PRODUCT, DRIVER, CUSTOMER }

    static final Duration WINDOW = Duration.ofDays(7);

    public record Rated(Subject subject, long subjectId, int stars) {}

    public record ReviewView(long id, long orderId, long authorId, Subject subjectType, long subjectId, int stars,
            String comment, Instant createdAt) {}

    public record SubjectReviews(Rating rating, List<ReviewView> reviews) {}

    private record OrderFacts(long id, long customerId, Long driverId, long shopId, String status, Instant deliveredAt) {}

    private final JdbcClient jdbc;

    Reviews(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** The customer's review. driverStars is ignored when no rider delivered the order. */
    @Transactional
    public List<ReviewView> fromCustomer(long customerId, long orderId, Integer shopStars, Integer driverStars,
            List<Rated> products, String comment) {
        OrderFacts order = deliveredOrder(orderId);
        if (order.customerId() != customerId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        Set<Long> received = new HashSet<>(jdbc.sql(
                "select product_id from order_items where order_id = :o and not unavailable").param("o", orderId)
                .query(Long.class).list());
        List<Rated> ratings = new ArrayList<>();
        if (shopStars != null) {
            ratings.add(new Rated(Subject.SHOP, order.shopId(), shopStars));
        }
        if (driverStars != null && order.driverId() != null) {
            ratings.add(new Rated(Subject.DRIVER, order.driverId(), driverStars));
        }
        for (Rated product : products) {
            if (!received.contains(product.subjectId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Product " + product.subjectId() + " was not delivered in this order");
            }
            ratings.add(new Rated(Subject.PRODUCT, product.subjectId(), product.stars()));
        }
        return save(order, customerId, ratings, comment);
    }

    /** The rider's review of the shop and the customer, for an order they delivered. */
    @Transactional
    public List<ReviewView> fromDriver(long driverId, long orderId, Integer shopStars, Integer customerStars, String comment) {
        OrderFacts order = deliveredOrder(orderId);
        if (order.driverId() == null || order.driverId() != driverId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found");
        }
        List<Rated> ratings = new ArrayList<>();
        if (shopStars != null) {
            ratings.add(new Rated(Subject.SHOP, order.shopId(), shopStars));
        }
        if (customerStars != null) {
            ratings.add(new Rated(Subject.CUSTOMER, order.customerId(), customerStars));
        }
        return save(order, driverId, ratings, comment);
    }

    public List<ReviewView> byAuthor(long authorId, long orderId) {
        return jdbc.sql("select * from reviews where author_id = :a and order_id = :o order by id")
                .param("a", authorId).param("o", orderId).query((rs, row) -> view(rs)).list();
    }

    public SubjectReviews forSubject(Subject subject, long subjectId, int limit, long offset) {
        Rating rating = jdbc.sql("select stars_sum, reviews from rating_totals where subject_type = :t and subject_id = :id")
                .param("t", subject.name()).param("id", subjectId)
                .query((rs, row) -> Rating.of(rs.getLong(1), rs.getInt(2))).optional().orElse(Rating.of(0, 0));
        List<ReviewView> reviews = jdbc.sql("""
                select * from reviews where subject_type = :t and subject_id = :id
                order by id desc limit :limit offset :offset
                """)
                .param("t", subject.name()).param("id", subjectId).param("limit", limit).param("offset", offset)
                .query((rs, row) -> view(rs)).list();
        return new SubjectReviews(rating, reviews);
    }

    /** Admin removes a review (abuse, mistakes); the average is corrected in the same step. */
    @Transactional
    public void delete(long reviewId) {
        Rated removed = jdbc.sql("delete from reviews where id = :id returning subject_type, subject_id, stars")
                .param("id", reviewId)
                .query((rs, row) -> new Rated(Subject.valueOf(rs.getString(1)), rs.getLong(2), rs.getInt(3)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found"));
        jdbc.sql("""
                update rating_totals set stars_sum = stars_sum - :stars, reviews = reviews - 1
                where subject_type = :t and subject_id = :id
                """)
                .param("stars", removed.stars()).param("t", removed.subject().name()).param("id", removed.subjectId())
                .update();
    }

    private List<ReviewView> save(OrderFacts order, long authorId, List<Rated> ratings, String comment) {
        if (ratings.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rate at least one thing");
        }
        if (ratings.stream().anyMatch(r -> r.stars() < 1 || r.stars() > 5)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stars must be 1 to 5");
        }
        if (ratings.stream().map(r -> r.subject() + ":" + r.subjectId()).distinct().count() != ratings.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each product can be rated once");
        }
        boolean already = jdbc.sql("select count(*) from reviews where order_id = :o and author_id = :a")
                .param("o", order.id()).param("a", authorId).query(Integer.class).single() > 0;
        if (already) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You have already reviewed this order");
        }
        String text = comment == null || comment.isBlank() ? null : comment.trim();
        for (Rated r : ratings.stream().sorted(Comparator.comparing(Rated::subject).thenComparingLong(Rated::subjectId)).toList()) {
            // The unique index on (order, author, subject) is the real guard if two submissions race.
            jdbc.sql("""
                    insert into reviews (order_id, author_id, subject_type, subject_id, stars, comment)
                    values (:o, :a, :t, :id, :stars, :comment)
                    """)
                    .param("o", order.id()).param("a", authorId).param("t", r.subject().name())
                    .param("id", r.subjectId()).param("stars", r.stars()).param("comment", text).update();
            jdbc.sql("""
                    insert into rating_totals (subject_type, subject_id, stars_sum, reviews) values (:t, :id, :stars, 1)
                    on conflict (subject_type, subject_id)
                    do update set stars_sum = rating_totals.stars_sum + excluded.stars_sum, reviews = rating_totals.reviews + 1
                    """)
                    .param("t", r.subject().name()).param("id", r.subjectId()).param("stars", r.stars()).update();
        }
        return byAuthor(authorId, order.id());
    }

    private OrderFacts deliveredOrder(long orderId) {
        // The per-subject unique constraint cannot catch concurrent submissions with different subjects.
        OrderFacts order = jdbc.sql("select id, customer_id, driver_id, shop_id, status, delivered_at from orders where id = :o for update")
                .param("o", orderId)
                .query((rs, row) -> new OrderFacts(rs.getLong("id"), rs.getLong("customer_id"),
                        (Long) rs.getObject("driver_id"), rs.getLong("shop_id"), rs.getString("status"),
                        rs.getTimestamp("delivered_at") == null ? null : rs.getTimestamp("delivered_at").toInstant()))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!"DELIVERED".equals(order.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only delivered orders can be reviewed");
        }
        if (order.deliveredAt().isBefore(Instant.now().minus(WINDOW))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reviews close 7 days after delivery");
        }
        return order;
    }

    private static ReviewView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new ReviewView(rs.getLong("id"), rs.getLong("order_id"), rs.getLong("author_id"),
                Subject.valueOf(rs.getString("subject_type")), rs.getLong("subject_id"), rs.getInt("stars"),
                rs.getString("comment"), created.toInstant());
    }
}
