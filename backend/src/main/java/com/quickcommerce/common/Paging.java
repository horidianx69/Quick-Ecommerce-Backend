package com.quickcommerce.common;

import org.springframework.data.domain.PageRequest;

public final class Paging {

    public static final int MAX_SIZE = 100;

    private Paging() {}

    /**
     * Builds the page request from plain numbers, clamped to sane bounds.
     * Clients cannot choose the sort: every list query has its own fixed order,
     * so there is no sort string to validate or to inject into.
     */
    public static PageRequest of(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_SIZE));
    }
}
