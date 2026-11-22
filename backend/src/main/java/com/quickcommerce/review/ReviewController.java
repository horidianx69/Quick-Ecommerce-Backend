package com.quickcommerce.review;

import com.quickcommerce.review.Reviews.Rated;
import com.quickcommerce.review.Reviews.ReviewView;
import com.quickcommerce.review.Reviews.Subject;
import com.quickcommerce.review.Reviews.SubjectReviews;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reviews: customers and riders write them; admin reads and moderates them. */
@RestController
public class ReviewController {

    public record ProductStars(@NotNull Long productId, @NotNull @Min(1) @Max(5) Integer stars) {}

    /** Every field is optional, but at least one rating is needed. */
    public record CustomerReview(@Min(1) @Max(5) Integer shopStars, @Min(1) @Max(5) Integer driverStars,
            @Size(max = 50) List<@Valid @NotNull ProductStars> products, @Size(max = 1000) String comment) {}

    public record DriverReview(@Min(1) @Max(5) Integer shopStars, @Min(1) @Max(5) Integer customerStars,
            @Size(max = 1000) String comment) {}

    private final Reviews reviews;

    ReviewController(Reviews reviews) {
        this.reviews = reviews;
    }

    /** The customer reviews a delivered order: the shop, the rider, and products they received (catalog product ids). */
    @PostMapping("/api/orders/{id}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    List<ReviewView> customerReview(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @Valid @RequestBody CustomerReview req) {
        List<Rated> products = req.products() == null ? List.of()
                : req.products().stream().map(p -> new Rated(Subject.PRODUCT, p.productId(), p.stars())).toList();
        return reviews.fromCustomer(user(jwt), id, req.shopStars(), req.driverStars(), products, req.comment());
    }

    /** What the customer wrote for this order. Empty = not reviewed yet. */
    @GetMapping("/api/orders/{id}/reviews")
    List<ReviewView> myCustomerReviews(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return reviews.byAuthor(user(jwt), id);
    }

    /** The rider reviews the shop and the customer of an order they delivered. */
    @PostMapping("/api/drivers/me/deliveries/{orderId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    List<ReviewView> driverReview(@AuthenticationPrincipal Jwt jwt, @PathVariable long orderId,
            @Valid @RequestBody DriverReview req) {
        return reviews.fromDriver(user(jwt), orderId, req.shopStars(), req.customerStars(), req.comment());
    }

    /** Reviews of one shop, product, driver or customer, newest first, with the average. */
    @GetMapping("/api/admin/reviews")
    SubjectReviews forSubject(@RequestParam Subject subjectType, @RequestParam long subjectId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        int limit = Math.clamp(size, 1, 100);
        return reviews.forSubject(subjectType, subjectId, limit, Math.max(page, 0) * limit);
    }

    @DeleteMapping("/api/admin/reviews/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        reviews.delete(id);
    }

    private static long user(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
