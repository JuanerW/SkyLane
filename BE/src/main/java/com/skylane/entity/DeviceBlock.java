package com.skylane.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_block", indexes = @Index(name = "device_block_order_idx", columnList = "order_id"))
@Getter @Setter @NoArgsConstructor
public class DeviceBlock extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private DeliveryOrder order;

    @Convert(converter = DeviceBlockKindConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private DeviceBlockKind kind;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "released_at")
    private Instant releasedAt;
}





