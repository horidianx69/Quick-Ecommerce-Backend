package com.quickcommerce.dispatch;

import com.quickcommerce.dispatch.DispatchService.DeliveryView;
import com.quickcommerce.dispatch.DispatchService.JobView;
import com.quickcommerce.dispatch.DispatchService.PastDelivery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The driver's working screens: job list, the current delivery and its steps, and past deliveries. */
@RestController
@RequestMapping("/api/drivers/me")
public class DeliveryController {

    /** The 4-digit code the customer reads out at the door. */
    public record DeliveredRequest(@NotNull @Pattern(regexp = "^\\d{4}$", message = "must be 4 digits") String code) {}

    private final DispatchService dispatch;

    DeliveryController(DispatchService dispatch) {
        this.dispatch = dispatch;
    }

    /** Open jobs near the driver, oldest first. The app polls this every few seconds while idle. */
    @GetMapping("/jobs")
    List<JobView> jobs(@AuthenticationPrincipal Jwt jwt) {
        return dispatch.jobs(id(jwt));
    }

    /** 409 if another driver was faster, or if this driver already has a delivery. */
    @PostMapping("/jobs/{orderId}/accept")
    DeliveryView accept(@AuthenticationPrincipal Jwt jwt, @PathVariable Long orderId) {
        return dispatch.accept(id(jwt), orderId);
    }

    /** The delivery in progress. 404 when there is none. */
    @GetMapping("/delivery")
    DeliveryView current(@AuthenticationPrincipal Jwt jwt) {
        return dispatch.current(id(jwt));
    }

    /** Gives the job back before pickup, so another driver can take it. */
    @PostMapping("/delivery/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@AuthenticationPrincipal Jwt jwt) {
        dispatch.release(id(jwt));
    }

    /** The bag is collected. Works only once the shop has marked the order ready. */
    @PostMapping("/delivery/picked-up")
    DeliveryView pickedUp(@AuthenticationPrincipal Jwt jwt) {
        return dispatch.pickedUp(id(jwt));
    }

    @PostMapping("/delivery/delivered")
    DeliveryView delivered(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeliveredRequest req) {
        return dispatch.delivered(id(jwt), req.code());
    }

    /** Finished deliveries with what each one paid, newest first. */
    @GetMapping("/deliveries")
    Page<PastDelivery> history(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return dispatch.history(id(jwt), page, size);
    }

    private static Long id(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
