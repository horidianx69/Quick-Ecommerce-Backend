package com.quickcommerce.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {

    public enum Role { SHOP, ADMIN, CUSTOMER, DRIVER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String email;
    private String passwordHash;
    private String name;
    @Enumerated(EnumType.STRING)
    private Role role;
    /** Suspended by admin. Changed only by UserRepository.setDisabled. */
    @Column(insertable = false, updatable = false)
    private boolean disabled;
    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
