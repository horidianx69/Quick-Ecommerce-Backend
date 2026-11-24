package com.quickcommerce.common;

import com.quickcommerce.auth.Throttle;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A ceiling on requests per minute for every request: per user once logged in,
 * per IP address otherwise. Runs after the token is checked, so a user is
 * counted the same from any device. The tighter limits on login, checkout,
 * location pings and delivery codes still apply on top. Not a Spring bean on
 * purpose: SecurityConfig places it in the security chain, and only there.
 */
// ponytail: counts live in this server's memory (Throttle). With several
// servers each counts on its own; move to a shared store (Redis) then.
public class RequestRateLimit extends OncePerRequestFilter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Throttle throttle;
    private final int perUser;
    private final int perIp;

    public RequestRateLimit(Throttle throttle, int perUser, int perIp) {
        this.throttle = throttle;
        this.perUser = perUser;
        this.perIp = perIp;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean allowed = auth != null && auth.getPrincipal() instanceof Jwt jwt
                ? throttle.tryAcquire("req-user:" + jwt.getSubject(), perUser, WINDOW, Instant.now())
                : throttle.tryAcquire("req-ip:" + request.getRemoteAddr(), perIp, WINDOW, Instant.now());
        if (allowed) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setContentType("application/problem+json");
        response.getWriter().write(
                "{\"status\":429,\"title\":\"Too Many Requests\",\"detail\":\"Too many requests. Wait a minute and try again.\"}");
    }
}
