package com.skylane.repository;

import com.skylane.entity.DeliveryReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryReviewRepository extends JpaRepository<DeliveryReview, UUID> {
    Optional<DeliveryReview> findByOrder_Id(UUID orderId);
    boolean existsByOrder_Id(UUID orderId);
}
