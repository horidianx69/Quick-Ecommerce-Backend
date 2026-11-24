package com.quickcommerce.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminBootstrapTest {

    @Test
    void aShortAdminPasswordStopsStartup() {
        assertThrows(IllegalStateException.class, () -> new AdminBootstrap(null, null, null, "a@b.ca", "short-pass1"));
        new AdminBootstrap(null, null, null, "a@b.ca", "long-enough-pass"); // 12+ is fine
        new AdminBootstrap(null, null, null, "", "").run(null);
    }

    @Test
    void bootstrapNormalizesAndValidatesNewAdminEmail() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var invalid = new AdminBootstrap(users, encoder, factory.getValidator(), "invalid-address", "long-enough-pass");
            assertThrows(IllegalStateException.class, () -> invalid.run(null));
            verify(users, never()).save(any());

            var valid = new AdminBootstrap(users, encoder, factory.getValidator(), "  ÉLISE@EXAMPLE.TEST  ", "long-enough-pass");
            valid.run(null);
            verify(users).save(argThat(user -> user.getEmail().equals("élise@example.test") && user.getRole() == User.Role.ADMIN));
        }
    }

    @Test
    void aRegisteredShopEmailCannotBeBootstrappedAsAdmin() {
        UserRepository users = mock(UserRepository.class);
        User shop = new User();
        shop.setRole(User.Role.SHOP);
        when(users.findByEmail("owner@example.test")).thenReturn(Optional.of(shop));
        var setup = new AdminBootstrap(users, null, null, " OWNER@EXAMPLE.TEST ", "long-enough-pass");
        assertThrows(IllegalStateException.class, () -> setup.run(null));
        verify(users, never()).save(any());
    }
}
