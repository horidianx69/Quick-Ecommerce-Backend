package com.quickcommerce.auth;

import com.quickcommerce.auth.User.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record SignupRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 100) String name,
            Role role) {}

    public record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password) {}

    static final int LOGIN_ATTEMPTS_PER_EMAIL = 5;
    static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);

    public record UserView(Long id, String email, String name, Role role) {
        static UserView of(User u) {
            return new UserView(u.getId(), u.getEmail(), u.getName(), u.getRole());
        }
    }

    public record AuthResponse(String token, UserView user) {}

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final Throttle throttle;
    private final Duration tokenTtl;
    private final int ipLimitPerMinute;

    AuthController(UserRepository users, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
            Throttle throttle, @Value("${app.jwt.ttl}") Duration tokenTtl,
            @Value("${app.auth.ip-limit-per-minute}") int ipLimitPerMinute) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.throttle = throttle;
        this.tokenTtl = tokenTtl;
        this.ipLimitPerMinute = ipLimitPerMinute;
    }

    /**
     * Public signup makes a SHOP user (the default), a CUSTOMER or a DRIVER.
     * It can never make an ADMIN: those come from AdminBootstrap. A new SHOP or
     * DRIVER login can do nothing until admin approves the shop or driver profile.
     */
    // ponytail: customers use email + password for now. Phone OTP login replaces
    // this once an SMS provider is chosen.
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    AuthResponse signup(@Valid @RequestBody SignupRequest req, HttpServletRequest http) {
        limitByIp(http);
        requireBcryptSafe(req.password());
        Role role = req.role() == null ? Role.SHOP : req.role();
        if (role == Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role must be SHOP, CUSTOMER or DRIVER");
        }
        String email = normalize(req.email());
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setName(req.name().trim());
        user.setRole(role);
        return respond(users.save(user));
    }

    @PostMapping("/login")
    AuthResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        limitByIp(http);
        requireBcryptSafe(req.password());
        String email = normalize(req.email());
        // The attempt is counted before the password check, so a burst of parallel
        // guesses cannot all get through. A correct login clears the count.
        if (!throttle.tryAcquire("login:" + email, LOGIN_ATTEMPTS_PER_EMAIL, LOGIN_WINDOW, Instant.now())) {
            throw tooManyRequests();
        }
        User user = users.findByEmail(email)
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                // Same answer for "no such email" and "wrong password".
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong email or password"));
        throttle.reset("login:" + email);
        return respond(user);
    }

    /**
     * Caps signups and logins per client IP, against account spam and password
     * guessing spread over many emails. Behind a load balancer the app must be
     * told to trust X-Forwarded-For (FORWARD_HEADERS_STRATEGY=native), otherwise
     * every user appears to come from the balancer's one IP.
     */
    private void limitByIp(HttpServletRequest http) {
        if (!throttle.tryAcquire("ip:" + http.getRemoteAddr(), ipLimitPerMinute, Duration.ofMinutes(1), Instant.now())) {
            throw tooManyRequests();
        }
    }

    private static ResponseStatusException tooManyRequests() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts, try again later");
    }

    /** BCrypt only reads 72 bytes. @Size counts characters, so multi-byte passwords need this check. */
    private static void requireBcryptSafe(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is too long");
        }
    }

    @GetMapping("/me")
    UserView me(@AuthenticationPrincipal Jwt jwt) {
        return users.findById(Long.valueOf(jwt.getSubject()))
                .map(UserView::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    // ponytail: one access token, no refresh token and no revocation; a role change
    // or deleted user takes effect when the token expires (app.jwt.ttl). Add refresh
    // tokens when the mobile apps need long sessions.
    private AuthResponse respond(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(now.plus(tokenTtl))
                .claim("scope", user.getRole().name())
                .build();
        String token = jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new AuthResponse(token, UserView.of(user));
    }

    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
