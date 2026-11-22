package com.quickcommerce.admin;

import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The admin home screen: what needs attention right now, in one call. */
// ponytail: plain counts over whole tables. Fine for a dashboard opened now and
// then; add indexes on created_at / delivered_at if it slows down as orders pile up.
@RestController
public class AdminStatsController {

    /** The first five are orders. needsDriver = accepted or ready with no driver: the number to watch. */
    public record Stats(long waitingForShop, long needsDriver, long onTheRoad, long placedLast24h, long deliveredLast24h,
            long shopsPending, long driversPending, long driversOnline, long productRequestsPending, long driverAlertsOpen) {}

    private static final String SQL = """
            select
              (select count(*) from orders where status = 'PLACED'),
              (select count(*) from orders where driver_id is null and status in ('ACCEPTED', 'READY')),
              (select count(*) from orders where status = 'PICKED_UP'),
              (select count(*) from orders where created_at > now() - interval '24 hours'),
              (select count(*) from orders where status = 'DELIVERED' and delivered_at > now() - interval '24 hours'),
              (select count(*) from shops where status = 'PENDING'),
              (select count(*) from drivers where status = 'PENDING'),
              (select count(*) from drivers where online),
              (select count(*) from catalog_products where status = 'PENDING'),
              (select count(*) from driver_alerts where resolved_at is null)
            """;

    private final JdbcClient jdbc;

    AdminStatsController(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @GetMapping("/api/admin/stats")
    Stats stats() {
        return jdbc.sql(SQL).query((rs, row) -> new Stats(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10))).single();
    }
}
