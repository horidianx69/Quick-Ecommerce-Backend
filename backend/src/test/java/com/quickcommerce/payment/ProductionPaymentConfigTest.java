package com.quickcommerce.payment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionPaymentConfigTest {

    @Test
    void productionRequiresLivePaymentsWhileDevelopmentCanUseTheLegacyFlow() {
        var environment = new MockEnvironment();
        assertDoesNotThrow(() -> new OrderPayments(null, environment, false, false, ""));
        environment.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new OrderPayments(null, environment, false, false, ""));
        assertThrows(IllegalStateException.class,
                () -> new OrderPayments(null, environment, true, false, "https://quike.example/checkout"));
        assertDoesNotThrow(() -> new OrderPayments(null, environment, true, true, "https://quike.example/checkout"));
    }
}
