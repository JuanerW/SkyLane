package com.skylane.repository;

import com.skylane.entity.DeliveryOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryOrderRepository extends JpaRepository<DeliveryOrder, UUID> {
    Optional<DeliveryOrder> findByIdAndUser_Id(UUID id, UUID userId);
    List<DeliveryOrder> findByUser_IdOrderByCreatedAtDesc(UUID userId);
    Optional<DeliveryOrder> findByUser_IdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
