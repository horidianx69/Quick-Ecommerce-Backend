package com.quickcommerce.dispatch;

import com.quickcommerce.common.Geo;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-only SQL for the driver's job list. One statement per call, no state. */
// ponytail: looks at every open job, in any city, and filters by distance.
// The open-job pool is small (orders leave it as soon as a driver accepts), so
// this is fine into the hundreds of open jobs. Add a location box filter when
// the pool across all cities grows past that.
@Repository
class DispatchQueries {

    record JobRow(long orderId, String status, Instant createdAt, int deliveryFeeCents, String city,
            double pickupKm, double dropKm, int units) {}

    /**
     * "materialized" pins the order of work (open jobs first, then their shops),
     * so Postgres cannot pick a slow plan when the query runs with bound parameters.
     * The first WHERE must stay word for word as in the index orders_open_jobs_idx.
     */
    private static final String OPEN_JOBS = """
            with open_jobs as materialized (
                select o.id, o.shop_id, o.status, o.created_at, o.delivery_fee_cents, o.city, o.latitude, o.longitude
                from orders o
                where o.driver_id is null and o.status in ('ACCEPTED', 'READY')
            ), near as materialized (
                select j.id, j.status, j.created_at, j.delivery_fee_cents, j.city,
                       %s as pickup_km,
                       %s as drop_km
                from open_jobs j
                join shops s on s.id = j.shop_id
            )
            select n.*,
                   (select coalesce(sum(i.quantity), 0) from order_items i
                    where i.order_id = n.id and not i.unavailable) as units
            from near n
            where n.pickup_km <= :radiusKm
            order by n.id
            limit 20
            """.formatted(
            Geo.kmSql("s.latitude", "s.longitude", ":lat", ":lng"),
            Geo.kmSql("s.latitude", "s.longitude", "j.latitude", "j.longitude"));

    private final JdbcClient jdbc;

    DispatchQueries(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** Jobs whose shop is within radiusKm of the driver, oldest first (first in, first out). */
    List<JobRow> openJobsNear(double lat, double lng, double radiusKm) {
        return jdbc.sql(OPEN_JOBS)
                .param("lat", lat)
                .param("lng", lng)
                .param("radiusKm", radiusKm)
                .query((rs, row) -> new JobRow(rs.getLong("id"), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(), rs.getInt("delivery_fee_cents"), rs.getString("city"),
                        rs.getDouble("pickup_km"), rs.getDouble("drop_km"), rs.getInt("units")))
                .list();
    }
}
