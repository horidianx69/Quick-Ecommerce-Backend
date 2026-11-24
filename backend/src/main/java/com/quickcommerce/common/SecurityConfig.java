package com.quickcommerce.common;

import static org.springframework.security.config.Customizer.withDefaults;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.quickcommerce.auth.DisabledUsers;
import com.quickcommerce.auth.Throttle;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    /**
     * The token's "scope" claim holds the role, which Spring turns into the
     * authority SCOPE_&lt;ROLE&gt;. Route rules live here, in one place.
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, Throttle throttle,
            @Value("${app.rate-limit.per-user-per-minute}") int perUser,
            @Value("${app.rate-limit.per-ip-per-minute}") int perIp) throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // bearer tokens only, no cookies
                .cors(withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/payments/stripe/webhook").permitAll()
                        .requestMatchers("/api/auth/signup", "/api/auth/login", "/error",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/health").permitAll()
                        .requestMatchers("/api/admin/**").hasAuthority("SCOPE_ADMIN")
                        .requestMatchers("/api/shops/me/**").hasAuthority("SCOPE_SHOP")
                        .requestMatchers("/api/orders/**", "/api/browse/**").hasAuthority("SCOPE_CUSTOMER")
                        .requestMatchers("/api/drivers/me/**").hasAuthority("SCOPE_DRIVER")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(withDefaults()))
                .addFilterAfter(new RequestRateLimit(throttle, perUser, perIp), BearerTokenAuthenticationFilter.class)
                .headers(headers -> headers
                        // On top of Spring's defaults (nosniff, DENY framing, no-store caching, HSTS over HTTPS):
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        // The API only ever returns data: a browser may load nothing from it and frame nothing.
                        // Not on the Swagger page, which needs its own scripts (and is off in production).
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                request -> request.getRequestURI().startsWith("/api/"),
                                new StaticHeadersWriter("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))))
                .build();
    }

    @Bean
    SecretKey jwtKey(@Value("${app.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtKey, DisabledUsers disabledUsers) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
        // On top of the usual checks (signature, expiry): a suspended user's
        // tokens stop working at once, not when they expire.
        OAuth2TokenValidator<Jwt> notSuspended = jwt -> isSuspended(jwt, disabledUsers)
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Account suspended", null))
                : OAuth2TokenValidatorResult.success();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), notSuspended));
        return decoder;
    }

    private static boolean isSuspended(Jwt jwt, DisabledUsers disabledUsers) {
        try {
            return disabledUsers.contains(Long.parseLong(jwt.getSubject()));
        } catch (NumberFormatException e) {
            return true; // every token we sign has a numeric user id
        }
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") List<String> origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
