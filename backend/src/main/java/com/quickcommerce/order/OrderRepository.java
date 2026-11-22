package com.quickcommerce.order;

import com.quickcommerce.order.Order.Status;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByCustomerIdAndIdempotencyKey(Long customerId, String idempotencyKey);

    Optional<Order> findByIdAndCustomerId(Long id, Long customerId);

    Optional<Order> findByIdAndShopId(Long id, Long shopId);

    Page<Order> findByCustomerId(Long customerId, Pageable pageable);

    Page<Order> findByShopId(Long shopId, Pageable pageable);

    Page<Order> findByShopIdAndStatus(Long shopId, Status status, Pageable pageable);

    long countByCustomerIdAndStatus(Long customerId, Status status);

    List<Order> findTop200ByStatusAndCreatedAtBeforeOrderById(Status status, Instant cutoff);

    /**
     * The only way status changes. It changes the row only if the order is
     * still in the expected status, so when two actions race (shop accepts
     * while the customer cancels) exactly one gets 1 back and the other gets 0.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Order o set o.status = :to where o.id = :id and o.status = :from")
    int move(Long id, Status from, Status to);

    /** Cancels only if the order is still in the status the caller saw. reason null = the customer cancelled. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Order o set o.status = :cancelled, o.cancelReason = :reason where o.id = :id and o.status = :from")
    int cancel(Long id, Status from, Status cancelled, String reason);

    /** Adds a tip once: only while none is set, the order is not closed, and within 24 h of delivery. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Order o set o.tipCents = :tip
            where o.id = :id and o.customerId = :customerId and o.tipCents = 0 and o.status not in :closed
              and (o.deliveredAt is null or o.deliveredAt > :cutoff)
            """)
    int tip(Long id, Long customerId, int tip, Collection<Status> closed, Instant cutoff);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Order o set o.receiptStatus = 'ASKED' where o.id = :id and o.receiptStatus is null")
    int askReceipt(Long id);

    /** Records the customer's answer once: to an ASKED question, or a report made within 24 h of delivery. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Order o set o.receiptStatus = :answer
            where o.id = :id and o.customerId = :customerId and o.status = :delivered
              and (o.receiptStatus = 'ASKED' or (o.receiptStatus is null and o.deliveredAt > :cutoff))
            """)
    int answerReceipt(Long id, Long customerId, String answer, Status delivered, Instant cutoff);

    // ------------------------------------------------------- driver steps

    /** Admin's assignment: sets the driver whether or not the order already had one. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Order o set o.driverId = :driverId where o.id = :id and o.status in :statuses")
    int setDriver(Long id, Long driverId, Collection<Status> statuses);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Order o set o.driverId = null where o.id = :id and o.driverId is not null and o.status in :statuses")
    int clearDriver(Long id, Collection<Status> statuses);

    boolean existsByDriverIdAndStatusIn(Long driverId, Collection<Status> statuses);

    Optional<Order> findFirstByDriverIdAndStatusIn(Long driverId, Collection<Status> statuses);

    Page<Order> findByDriverIdAndStatus(Long driverId, Status status, Pageable pageable);

    /** Gives the order to a driver only if no driver has it yet: of many accepts at once, one gets 1 back. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Order o set o.driverId = :driverId where o.id = :id and o.driverId is null and o.status in :statuses")
    int assignDriver(Long id, Long driverId, Collection<Status> statuses);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Order o set o.driverId = null where o.id = :id and o.driverId = :driverId and o.status in :statuses")
    int releaseDriver(Long id, Long driverId, Collection<Status> statuses);

    /** A status step that only the order's own driver can make. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Order o set o.status = :to where o.id = :id and o.driverId = :driverId and o.status = :from")
    int moveForDriver(Long id, Long driverId, Status from, Status to);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Order o set o.status = :delivered, o.deliveredAt = :now
            where o.id = :id and o.driverId = :driverId and o.status = :pickedUp
            """)
    int deliver(Long id, Long driverId, Instant now, Status pickedUp, Status delivered);
}
