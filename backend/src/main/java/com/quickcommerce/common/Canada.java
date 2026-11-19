package com.quickcommerce.common;

/** Validation patterns for Canadian addresses, shared by shop profiles and delivery addresses. */
public final class Canada {

    public static final String PROVINCE = "^(AB|BC|MB|NB|NL|NS|NT|NU|ON|PE|QC|SK|YT)$";
    public static final String POSTAL_CODE = "^[A-Za-z]\\d[A-Za-z] ?\\d[A-Za-z]\\d$";

    private Canada() {}
}
