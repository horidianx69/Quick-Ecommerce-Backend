package com.quickcommerce.shop;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "shops")
@Getter
@Setter
public class Shop {

    public enum Status { PENDING, APPROVED, SUSPENDED }

    /** One row per open day. A day with no row means the shop is closed that day. */
    @Embeddable
    public record Hours(
            @NotNull @Enumerated(EnumType.STRING) @Column(name = "day_of_week") DayOfWeek dayOfWeek,
            @NotNull LocalTime opens,
            @NotNull LocalTime closes) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long ownerId;
    private String name;
    private String phone;
    private String addressLine;
    private String city;
    private String province;
    private String postalCode;

    /** The real location. For the owner, admin and (later) the assigned driver only. Set through moveTo. */
    @Setter(AccessLevel.NONE)
    private double latitude;
    @Setter(AccessLevel.NONE)
    private double longitude;

    /**
     * The location every customer-facing distance is measured from: the real
     * one moved 150 to 400 m in a random direction. If customers got exact
     * distances to the real point, three lookups from different places would
     * be enough to find the shop. Chosen once per real location and then kept,
     * because a fresh shift on every save could be averaged away.
     */
    @JsonIgnore
    @Setter(AccessLevel.NONE)
    private double publicLatitude;
    @JsonIgnore
    @Setter(AccessLevel.NONE)
    private double publicLongitude;

    /**
     * Written once at insert, then only by ShopRepository.setStatus. Saving this
     * entity never writes status, so an owner's profile save cannot put back an
     * old status over an admin's approval or suspension.
     */
    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private Status status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "shop_hours", joinColumns = @JoinColumn(name = "shop_id"))
    private List<Hours> hours = new ArrayList<>();

    /** Sets the real location, and picks a new public one only when the real one actually changed. */
    public void moveTo(double newLatitude, double newLongitude) {
        if (id == null || newLatitude != latitude || newLongitude != longitude) {
            double metres = 150 + RANDOM.nextDouble() * 250;
            double bearing = RANDOM.nextDouble() * 2 * Math.PI;
            double metresPerDegree = 111_320.0;
            publicLatitude = newLatitude + metres * Math.cos(bearing) / metresPerDegree;
            publicLongitude = newLongitude + metres * Math.sin(bearing)
                    / (metresPerDegree * Math.max(0.01, Math.cos(Math.toRadians(newLatitude))));
        }
        latitude = newLatitude;
        longitude = newLongitude;
    }
}
