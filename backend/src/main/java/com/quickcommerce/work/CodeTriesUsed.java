package com.quickcommerce.work;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 429: the driver used up the delivery-code tries. Transactions that throw it
 * still commit (noRollbackFor), so the violation recorded just before is kept.
 */
public class CodeTriesUsed extends ResponseStatusException {

    public CodeTriesUsed() {
        super(HttpStatus.TOO_MANY_REQUESTS, "Too many wrong codes. Contact support.");
    }
}
