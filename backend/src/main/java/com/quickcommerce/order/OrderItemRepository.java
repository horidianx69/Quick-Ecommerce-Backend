package com.quickcommerce.order;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderIdInOrderById(Collection<Long> orderIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OrderItem i set i.unavailable = true where i.orderId = :orderId and i.id in :ids")
    int markUnavailable(Long orderId, Collection<Long> ids);
}
