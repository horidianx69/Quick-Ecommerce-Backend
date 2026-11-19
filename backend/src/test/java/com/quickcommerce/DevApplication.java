package com.quickcommerce;

import org.springframework.boot.SpringApplication;

/**
 * Local run with a throwaway embedded Postgres and the dev profile:
 *
 *   mvn spring-boot:test-run
 *
 * API on http://localhost:8080, docs on /swagger-ui.html. The admin login is
 * in src/test/resources/application-dev.properties. Data resets on restart.
 */
public class DevApplication {

    public static void main(String[] args) {
        System.setProperty("spring.profiles.active", "dev");
        SpringApplication.from(QuickCommerceApplication::main).with(EmbeddedDb.class).run(args);
    }
}
