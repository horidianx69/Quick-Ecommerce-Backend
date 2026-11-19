package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Runs with the location ping limit set to 5 a minute (the other tests switch it off). */
@TestPropertySource(properties = "app.dispatch.max-pings-per-minute=5")
class PingThrottleTest extends ApiTestBase {

    @Test
    void aDriverCannotFloodTheServerWithLocationPings() {
        DriverAt driver = driverAt(adminToken(), 49.2, -122.86);
        for (int i = 0; i < 5; i++) {
            assertEquals(204, post("/api/drivers/me/location", driver.token(), position(49.2, -122.86)).status());
        }
        assertEquals(429, post("/api/drivers/me/location", driver.token(), position(49.2, -122.86)).status());
    }
}
