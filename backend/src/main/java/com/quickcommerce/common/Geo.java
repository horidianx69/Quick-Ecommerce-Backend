package com.quickcommerce.common;

/** Straight-line distance on Earth (haversine formula), in Java and as the same formula in SQL. */
public final class Geo {

    private Geo() {}

    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * The SQL expression for the distance in km between two points. Each
     * argument is a column name or a named parameter written by the caller
     * (never user input), for example kmSql("s.latitude", "s.longitude", ":lat", ":lng").
     */
    public static String kmSql(String lat1, String lng1, String lat2, String lng2) {
        return """
                (2 * 6371 * asin(least(1, sqrt(
                    power(sin(radians(%1$s - %3$s) / 2), 2)
                    + cos(radians(%3$s)) * cos(radians(%1$s))
                      * power(sin(radians(%2$s - %4$s) / 2), 2)))))""".formatted(lat1, lng1, lat2, lng2);
    }

    /** True for a real point on Earth. Written as !(inside) so NaN, which fails every comparison, is refused too. */
    public static boolean isValid(double lat, double lng) {
        return lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
    }
}
