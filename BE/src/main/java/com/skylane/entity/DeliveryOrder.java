package com.skylane.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "delivery_order",
       uniqueConstraints = @UniqueConstraint(name = "order_idem_uq", columnNames = {"user_id", "idempotency_key"}),
       indexes = {
           @Index(name = "order_user_created_idx", columnList = "user_id, created_at"),
           @Index(name = "order_status_idx", columnList = "status")
       })
@Getter @Setter @NoArgsConstructor
public class DeliveryOrder extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false)
    private Quote quote;

    @Column(name = "option_id", nullable = false)
    private UUID optionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Convert(converter = DeviceTypeConverter.class)
    @Column(name = "device_type", nullable = false, columnDefinition = "text")
    private DeviceType deviceType;

    @Convert(converter = OrderStatusConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private OrderStatus status = OrderStatus.CONFIRMED;

    @Column(name = "recipient_name", nullable = false, columnDefinition = "text")
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, columnDefinition = "text")
    private String recipientPhone;

    @Column(name = "price_cents", nullable = false)
    private Integer priceCents;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "pickup_text", nullable = false, columnDefinition = "text")
    private String pickupText;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "pickup_location", nullable = false, columnDefinition = "geography(Point,4326)")
    private Point pickupLocation;

    @Column(name = "dropoff_text", nullable = false, columnDefinition = "text")
    private String dropoffText;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "dropoff_location", nullable = false, columnDefinition = "geography(Point,4326)")
    private Point dropoffLocation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "route_points", nullable = false, columnDefinition = "jsonb")
    private List<List<Double>> routePoints;

    @Column(name = "departs_at", nullable = false)
    private Instant departsAt;

    @Column(name = "eta_at", nullable = false)
    private Instant etaAt;

    @Column(name = "idempotency_key", nullable = false, columnDefinition = "text")
    private String idempotencyKey;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "cancel_reason", columnDefinition = "text")
    private String cancelReason;

    @org.hibernate.annotations.UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}






