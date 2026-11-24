package com.quickcommerce.payment;

import com.stripe.StripeClient;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@ConditionalOnProperty(name = "app.payments.enabled", havingValue = "true")
class PaymentConfig {

    @Bean
    StripeClient stripeClient(@Value("${app.payments.api-key}") String key,
            @Value("${app.payments.live}") boolean live) {
        String mode = live ? "live" : "test";
        if (!(key.startsWith("sk_" + mode + "_") || key.startsWith("rk_" + mode + "_"))) {
            throw new IllegalStateException("Stripe API key must match the configured payment mode");
        }
        return StripeClient.builder().setApiKey(key).setConnectTimeout(2_000).setReadTimeout(5_000)
                .setMaxNetworkRetries(0).build();
    }

    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler paymentScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("stripe-worker-");
        return scheduler;
    }

    static String validateReturnUrl(String value, boolean live) {
        URI url = URI.create(value);
        boolean local = List.of("localhost", "127.0.0.1", "[::1]").contains(
                url.getHost() == null ? "" : url.getHost());
        if (url.getHost() == null || url.getUserInfo() != null || url.getQuery() != null
                || url.getFragment() != null || value.contains("{")
                || !("https".equals(url.getScheme()) || (!live && local && "http".equals(url.getScheme())))) {
            throw new IllegalStateException("Stripe return URL must use HTTPS, or local HTTP in test mode");
        }
        return url.toASCIIString();
    }
}
