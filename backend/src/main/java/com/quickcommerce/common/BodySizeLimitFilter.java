package com.quickcommerce.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses large request bodies before anything reads them. Without this, each
 * request may send megabytes of JSON that the server would load into memory.
 * Runs first, ahead of the security filters.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BodySizeLimitFilter extends OncePerRequestFilter {

    /** The largest real body (a shop profile with hours) is under 2 KB. Images will go straight to object storage. */
    static final long MAX_BODY_BYTES = 64 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        } else if (request.getHeader("Transfer-Encoding") != null) {
            // A chunked body has no declared size to check, so ask for one.
            response.setStatus(HttpServletResponse.SC_LENGTH_REQUIRED);
        } else {
            chain.doFilter(request, response);
        }
    }
}
