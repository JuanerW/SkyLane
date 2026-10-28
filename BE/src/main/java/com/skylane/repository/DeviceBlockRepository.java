package com.skylane.repository;

import com.skylane.entity.DeviceBlock;
import com.skylane.entity.DeviceBlockKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceBlockRepository extends JpaRepository<DeviceBlock, UUID> {
    List<DeviceBlock> findByDevice_IdAndReleasedAtIsNullOrderByEndsAtAsc(UUID deviceId);
    List<DeviceBlock> findByOrder_IdAndReleasedAtIsNullOrderByStartsAtAsc(UUID orderId);
    Optional<DeviceBlock> findFirstByOrder_IdAndKindAndReleasedAtIsNull(UUID orderId, DeviceBlockKind kind);
}
