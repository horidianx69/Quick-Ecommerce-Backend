package com.quickcommerce.order;

import com.quickcommerce.common.Geo;
import com.quickcommerce.common.Settings;
import com.quickcommerce.shop.Shop;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Whether the surge fee applies to a new order at this shop. Admin can force it
 * ON or OFF; in AUTO it applies when the orders waiting for a driver near the
 * shop (counting this one) are at least surge_ratio_percent / 100 times the
 * approved drivers who are online near it. With no driver online nearby, any
 * order surges.
 */
// ponytail: two counts per order placed, over the open-job pool and the drivers
// table. Both stay small; precompute per area if they ever show in query times.
@Component
class SurgeCheck {

    private static final String SQL = """
            select
              (select count(*) from orders o join shops s on s.id = o.shop_id
                 where o.driver_id is null and o.status in ('ACCEPTED', 'READY')
                   and %s <= :radius) as jobs,
              (select count(*) from drivers d
                 where d.online and d.status = 'APPROVED' and d.located_at > now() - make_interval(secs => :fresh)
                   and %s <= :radius) as riders
            """.formatted(Geo.kmSql("s.latitude", "s.longitude", ":lat", ":lng"),
            Geo.kmSql("d.latitude", "d.longitude", ":lat", ":lng"));

    private final JdbcClient jdbc;
    private final double radiusKm;
    private final Duration locationMaxAge;

    SurgeCheck(DataSource dataSource, @Value("${app.dispatch.offer-radius-km}") double radiusKm,
            @Value("${app.dispatch.location-max-age}") Duration locationMaxAge) {
        this.jdbc = JdbcClient.create(dataSource);
        this.radiusKm = radiusKm;
        this.locationMaxAge = locationMaxAge;
    }

    boolean isActive(Shop shop, Settings.Values settings) {
        return switch (settings.surgeMode()) {
            case "ON" -> true;
            case "OFF" -> false;
            default -> {
                long[] counts = jdbc.sql(SQL)
                        .param("lat", shop.getLatitude()).param("lng", shop.getLongitude())
                        .param("radius", radiusKm).param("fresh", locationMaxAge.toSeconds())
                        .query((rs, row) -> new long[] {rs.getLong("jobs"), rs.getLong("riders")}).single();
                yield (counts[0] + 1) * 100 >= (long) settings.surgeRatioPercent() * counts[1];
            }
        };
    }
}
