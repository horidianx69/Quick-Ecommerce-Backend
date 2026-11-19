package com.quickcommerce;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Starts a real Postgres from downloaded binaries (no Docker, no install) and
 * hands it to Spring as the DataSource. The data is thrown away on shutdown.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EmbeddedDb {

    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres() throws IOException {
        return EmbeddedPostgres.start();
    }

    /**
     * The same HikariCP pool as production, reading the same
     * spring.datasource.hikari.* settings (pool size, timeouts), so tests and
     * load runs see the real behaviour under load.
     */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(EmbeddedPostgres postgres) {
        HikariDataSource pool = new HikariDataSource();
        pool.setDataSource(postgres.getPostgresDatabase());
        return pool;
    }
}
