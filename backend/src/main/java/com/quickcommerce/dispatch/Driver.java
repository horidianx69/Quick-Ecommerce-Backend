package com.quickcommerce.dispatch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * A delivery driver. Only phone is saved through the entity. Status, online
 * and position each change through their own single statement in
 * DriverRepository, so a location ping can never write an old status back,
 * and a profile save can never write an old position back.
 */
@Entity
@Table(name = "drivers")
@Getter
@Setter
public class Driver {

    public enum Status { PENDING, APPROVED, SUSPENDED }

    /** The same id as the user row. */
    @Id
    private Long userId;
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private Status status;
    @Column(updatable = false)
    private boolean online;
    /** The latest position only. Null until the driver first goes online. */
    @Column(updatable = false)
    private Double latitude;
    @Column(updatable = false)
    private Double longitude;
    @Column(updatable = false)
    private Instant locatedAt;
}
