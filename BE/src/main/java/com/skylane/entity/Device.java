package com.skylane.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "device", indexes = @Index(name = "device_station_type_idx", columnList = "station_id, device_type"))
@Getter @Setter @NoArgsConstructor
public class Device extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    @Column(nullable = false, unique = true, columnDefinition = "text")
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    @Convert(converter = DeviceTypeConverter.class)
    @Column(name = "device_type", nullable = false, columnDefinition = "text")
    private DeviceType deviceType;

    @Convert(converter = DeviceStatusConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private DeviceStatus status = DeviceStatus.ACTIVE;

    @Column(name = "battery_pct", precision = 5, scale = 2)
    private BigDecimal batteryPct;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(columnDefinition = "geography(Point,4326)")
    private Point location;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;
}




