package com.skylane.repository;

import com.skylane.entity.OrderEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderEventRepository extends JpaRepository<OrderEvent, Long> {
    List<OrderEvent> findByOrder_IdOrderByEventIdAsc(UUID orderId);
    List<OrderEvent> findByOrder_IdAndEventIdGreaterThanOrderByEventIdAsc(UUID orderId, long eventId);
}
