package com.quickcommerce.dispatch;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    @Query(value = """
            select new com.quickcommerce.dispatch.AdminDriverView(
                d.userId, u.name, u.email, d.phone, d.status, d.online)
            from Driver d join User u on u.id = d.userId
            where d.status = :status
            order by d.userId
            """,
            countQuery = "select count(d) from Driver d where d.status = :status")
    Page<AdminDriverView> list(Driver.Status status, Pageable pageable);

    /** Only an approved driver can go online. 0 rows = no profile, or not approved. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Driver d set d.online = true, d.latitude = :latitude, d.longitude = :longitude, d.locatedAt = :now
            where d.userId = :userId and d.status = :approved
            """)
    int goOnline(Long userId, double latitude, double longitude, Instant now, Driver.Status approved);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Driver d set d.online = false where d.userId = :userId")
    int goOffline(Long userId);

    /** The location ping: one small update, sent every few seconds by each online driver. */
    @Modifying
    @Query("""
            update Driver d set d.latitude = :latitude, d.longitude = :longitude, d.locatedAt = :now
            where d.userId = :userId and d.online = true
            """)
    int ping(Long userId, double latitude, double longitude, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Driver d set d.status = :status where d.userId = :userId")
    int setStatus(Long userId, Driver.Status status);
}
