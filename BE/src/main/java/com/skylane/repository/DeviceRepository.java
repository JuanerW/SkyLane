package com.skylane.repository;

import com.skylane.entity.Device;
import com.skylane.entity.DeviceStatus;
import com.skylane.entity.DeviceType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.QueryHint;

import java.util.List;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    java.util.Optional<Device> findByCode(String code);
    List<Device> findByStation_IdAndDeviceTypeAndStatusOrderByCodeAsc(
            UUID stationId, DeviceType deviceType, DeviceStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select d from Device d where d.station.id = :stationId and d.deviceType = :deviceType and d.status = :status order by d.code")
    List<Device> lockCandidatesForDispatch(
            @Param("stationId") UUID stationId,
            @Param("deviceType") DeviceType deviceType,
            @Param("status") DeviceStatus status);
}
