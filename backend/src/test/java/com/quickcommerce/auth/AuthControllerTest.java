package com.quickcommerce.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class AuthControllerTest {

    @Test
    void failedLoginChecksAPasswordHashEvenWhenTheAccountIsMissing() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("dummy-hash");
        User existing = new User();
        existing.setPasswordHash("account-hash");
        when(users.findByEmail("exists@example.test")).thenReturn(Optional.of(existing));
        AuthController auth = new AuthController(users, encoder, null, new Throttle(), Duration.ofHours(12), 60);
        var http = new MockHttpServletRequest();

        var unknown = assertThrows(ResponseStatusException.class, () -> auth.login(
                new AuthController.LoginRequest("unknown@example.test", "wrong-password"), http));
        var wrong = assertThrows(ResponseStatusException.class, () -> auth.login(
                new AuthController.LoginRequest("exists@example.test", "wrong-password"), http));

        verify(encoder).matches("wrong-password", "dummy-hash");
        verify(encoder).matches("wrong-password", "account-hash");
        assertEquals(HttpStatus.UNAUTHORIZED, unknown.getStatusCode());
        assertEquals(unknown.getStatusCode(), wrong.getStatusCode());
        assertEquals(unknown.getReason(), wrong.getReason());
    }
}
