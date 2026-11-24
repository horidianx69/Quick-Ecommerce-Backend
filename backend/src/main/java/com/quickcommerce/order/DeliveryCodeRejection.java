package com.quickcommerce.order;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A rejected delivery code still commits its attempt or violation. */
public final class DeliveryCodeRejection extends ResponseStatusException {

    private DeliveryCodeRejection(HttpStatus status, String reason) {
        super(status, reason);
    }

    static DeliveryCodeRejection wrongCode() {
        return new DeliveryCodeRejection(HttpStatus.BAD_REQUEST, "Wrong delivery code");
    }

    static DeliveryCodeRejection triesUsed() {
        return new DeliveryCodeRejection(HttpStatus.TOO_MANY_REQUESTS, "Too many wrong codes. Contact support.");
    }
}
