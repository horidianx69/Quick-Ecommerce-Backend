package com.quickcommerce.auth;

import com.quickcommerce.auth.User.Role;
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
    private final String email;
    private final String password;

    AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder,
            @Value("${app.admin.email}") String email, @Value("${app.admin.password}") String password) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.email = AuthController.normalize(email);
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isEmpty() || password.isEmpty() || users.existsByEmail(email)) {
            return;
        }
        User admin = new User();
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setName("Admin");
        admin.setRole(Role.ADMIN);
        users.save(admin);
    }
}
