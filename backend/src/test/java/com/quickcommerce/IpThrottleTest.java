package com.quickcommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Runs with the per-IP limit set to 5 a minute (the other tests switch it off). */
@TestPropertySource(properties = "app.auth.ip-limit-per-minute=5")
class IpThrottleTest extends ApiTestBase {

    /** No shared setup here: its admin login would count toward the very limit this test checks. */
    @Override
    void steadyFees() {}

    @Test
    void oneIpCannotSignUpOrLogInWithoutLimit() {
        for (int i = 0; i < 5; i++) {
            assertEquals(201, post("/api/auth/signup", null, signupJson(newEmail(), PASSWORD)).status());
        }
        assertEquals(429, post("/api/auth/signup", null, signupJson(newEmail(), PASSWORD)).status(),
                "the 6th signup from one IP in a minute is refused");
        assertEquals(429, post("/api/auth/login", null, loginJson(adminEmail, adminPassword)).status(),
                "logins share the same per-IP budget, so guessing across many emails is capped too");
    }
}
