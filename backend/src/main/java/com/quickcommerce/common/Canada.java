package com.quickcommerce.common;

/** Validation patterns for Canadian addresses, shared by shop profiles and delivery addresses. */
public final class Canada {

    public static final String PROVINCE = "^(AB|BC|MB|NB|NL|NS|NT|NU|ON|PE|QC|SK|YT)$";
    public static final String POSTAL_CODE = "^[A-Za-z]\\d[A-Za-z] ?\\d[A-Za-z]\\d$";

    private Canada() {}

    /**
     * The time zone a shop's opening hours are read in.
     *
     * ponytail: one zone per province. A few areas differ (north-west Ontario,
     * north-east BC, most of Nunavut). Add a per-shop override when a shop there signs up.
     */
    public static String timeZone(String province) {
        return switch (province) {
            case "BC" -> "America/Vancouver";
            case "AB" -> "America/Edmonton";
            case "SK" -> "America/Regina";
            case "MB" -> "America/Winnipeg";
            case "ON", "QC" -> "America/Toronto";
            case "NB", "NS", "PE" -> "America/Halifax";
            case "NL" -> "America/St_Johns";
            case "YT" -> "America/Whitehorse";
            case "NT" -> "America/Yellowknife";
            case "NU" -> "America/Iqaluit";
            default -> throw new IllegalArgumentException("Unknown province: " + province);
        };
    }
}
