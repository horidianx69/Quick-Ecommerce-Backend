package com.quickcommerce.dispatch;

import com.quickcommerce.auth.Throttle;
import com.quickcommerce.common.Geo;
import com.quickcommerce.common.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The driver's own account: profile, online switch and location pings. Plus admin's approval of drivers. */
@RestController
public class DriverController {

    public record ProfileRequest(
            @NotBlank @Pattern(regexp = "^\\+?[0-9 ()-]{7,20}$", message = "must be a phone number") String phone) {}

    /** The phone's GPS reading. */
    public record Position(@NotNull Double latitude, @NotNull Double longitude) {
        void requireValid() {
            if (!Geo.isValid(latitude, longitude)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "latitude must be -90..90 and longitude -180..180");
            }
        }
    }

    public record StatusRequest(@NotNull Driver.Status status) {}

    private final DriverRepository drivers;
    private final Throttle throttle;
    private final int maxPingsPerMinute;

    DriverController(DriverRepository drivers, Throttle throttle,
            @Value("${app.dispatch.max-pings-per-minute}") int maxPingsPerMinute) {
        this.drivers = drivers;
        this.throttle = throttle;
        this.maxPingsPerMinute = maxPingsPerMinute;
    }

    @GetMapping("/api/drivers/me")
    Driver me(@AuthenticationPrincipal Jwt jwt) {
        return drivers.findById(id(jwt)).orElseThrow(DriverController::noProfile);
    }

    /** Creates the driver profile (status PENDING, waiting for admin) or updates the phone. */
    @PutMapping("/api/drivers/me")
    @Transactional
    Driver saveProfile(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileRequest req) {
        Driver driver = drivers.findById(id(jwt)).orElseGet(() -> {
            Driver fresh = new Driver();
            fresh.setUserId(id(jwt));
            fresh.setStatus(Driver.Status.PENDING);
            return fresh;
        });
        driver.setPhone(req.phone().trim());
        return drivers.save(driver);
    }

    /** Start of a shift. The app sends its position so jobs near the driver can be offered at once. */
    @PostMapping("/api/drivers/me/online")
    @Transactional
    Driver goOnline(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Position position) {
        position.requireValid();
        Long userId = id(jwt);
        if (drivers.goOnline(userId, position.latitude(), position.longitude(), Instant.now(), Driver.Status.APPROVED) == 0) {
            throw drivers.existsById(userId)
                    ? new ResponseStatusException(HttpStatus.CONFLICT, "Your driver account is not approved")
                    : noProfile();
        }
        return drivers.findById(userId).orElseThrow();
    }

    @PostMapping("/api/drivers/me/offline")
    @Transactional
    Driver goOffline(@AuthenticationPrincipal Jwt jwt) {
        if (drivers.goOffline(id(jwt)) == 0) {
            throw noProfile();
        }
        return drivers.findById(id(jwt)).orElseThrow();
    }

    /**
     * The location ping. The app calls this every few seconds while online.
     * 409 means the driver is not online (went offline, or was suspended): the app should stop pinging.
     */
    // ponytail: every ping is one UPDATE on the driver's row. Fine for hundreds
    // of drivers. Keep positions in memory or Redis when pings reach thousands a second.
    @PostMapping("/api/drivers/me/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void ping(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Position position) {
        position.requireValid();
        Long userId = id(jwt);
        if (!throttle.tryAcquire("ping:" + userId, maxPingsPerMinute, Duration.ofMinutes(1), Instant.now())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many location updates");
        }
        if (drivers.ping(userId, position.latitude(), position.longitude(), Instant.now()) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You are not online");
        }
    }

    // ------------------------------------------------------------- admin

    /** Drivers by status. ?status=PENDING (the default) is the approval queue. */
    @GetMapping("/api/admin/drivers")
    Page<AdminDriverView> list(@RequestParam(defaultValue = "PENDING") Driver.Status status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return drivers.list(status, Paging.of(page, size));
    }

    /** Approve or suspend. A suspended driver is taken offline at once and is offered no more jobs. */
    @PutMapping("/api/admin/drivers/{userId}/status")
    @Transactional
    Driver setStatus(@PathVariable Long userId, @Valid @RequestBody StatusRequest req) {
        if (drivers.setStatus(userId, req.status()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }
        if (req.status() != Driver.Status.APPROVED) {
            drivers.goOffline(userId);
        }
        return drivers.findById(userId).orElseThrow();
    }

    private static Long id(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }

    private static ResponseStatusException noProfile() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Create your driver profile first");
    }
}
