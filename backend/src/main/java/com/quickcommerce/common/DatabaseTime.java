package com.quickcommerce.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Apply the same time zone rules as the SQL that checks shop hours and groups orders by day. */
@Component
public class DatabaseTime {

    private final JdbcClient jdbc;
    private final String businessZone;

    DatabaseTime(DataSource dataSource, @Value("${app.business-time-zone}") String businessZone) {
        this.jdbc = JdbcClient.create(dataSource);
        this.businessZone = businessZone;
    }

    public LocalDateTime localNow(String zone) {
        return jdbc.sql("select cast(now() at time zone :zone as timestamp)").param("zone", zone)
                .query(LocalDateTime.class).single();
    }

    public LocalDate today() {
        return localNow(businessZone).toLocalDate();
    }

    public Instant instant() {
        return jdbc.sql("select clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    public Instant dayStart(LocalDate date) {
        return jdbc.sql("select cast(:date as timestamp) at time zone :zone")
                .param("date", date).param("zone", businessZone).query(OffsetDateTime.class).single().toInstant();
    }
}
