package com.quickcommerce.shop;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ShopRepository extends JpaRepository<Shop, Long> {

    Optional<Shop> findByOwnerId(Long ownerId);

    /** Locks the shop row until the transaction ends, so two saves of one shop run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Shop> findWithLockByOwnerId(Long ownerId);

    Page<Shop> findByStatus(Shop.Status status, Pageable pageable);

    /** The only way status changes: one statement that touches no other column. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Shop s set s.status = :status where s.id = :id")
    int setStatus(Long id, Shop.Status status);
}
