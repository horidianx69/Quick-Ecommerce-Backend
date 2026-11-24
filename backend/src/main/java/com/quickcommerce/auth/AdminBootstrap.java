package com.quickcommerce.auth;

import com.quickcommerce.auth.User.Role;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the first ADMIN from config, since public signup only makes SHOP users. */
@Component
class AdminBootstrap implements ApplicationRunner {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;
    private final String email;
    private final String password;

    AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder, Validator validator,
            @Value("${app.admin.email}") String email, @Value("${app.admin.password}") String password) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
        this.email = AuthController.normalize(email);
        this.password = password;
        if (!password.isEmpty() && password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    /** Admin can change every price and suspend anyone, so its password must be long. */
    static final int MIN_PASSWORD_LENGTH = 12;

    @Override
    public void run(ApplicationArguments args) {
        if (email.isEmpty() || password.isEmpty()) {
            return;
        }
        var existing = users.findByEmail(email);
        if (existing.isPresent()) {
            if (existing.get().getRole() != Role.ADMIN) {
                throw new IllegalStateException("ADMIN_EMAIL is already registered to a non-admin account");
            }
            return;
        }
        if (!validator.validateValue(AuthController.SignupRequest.class, "email", email).isEmpty()) {
            throw new IllegalStateException("ADMIN_EMAIL must be a valid email address");
        }
        User admin = new User();
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setName("Admin");
        admin.setRole(Role.ADMIN);
        users.save(admin);
    }
}
