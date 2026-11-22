package com.quickcommerce.common;

/** An average star rating. average is null (and count 0) until the first review. */
public record Rating(Double average, int count) {

    public static Rating of(long starsSum, int count) {
        return count == 0 ? new Rating(null, 0) : new Rating(Math.round(starsSum * 10.0 / count) / 10.0, count);
    }
}
