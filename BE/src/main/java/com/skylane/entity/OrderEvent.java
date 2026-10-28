package com.skylane.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "order_event", indexes = @Index(name = "order_event_order_idx", columnList = "order_id, event_id"))
@Getter @Setter @NoArgsConstructor
public class OrderEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private DeliveryOrder order;

    @Convert(converter = OrderStatusConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private OrderStatus status;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Convert(converter = OrderActorTypeConverter.class)
    @Column(name = "actor_type", nullable = false, columnDefinition = "text")
    private OrderActorType actorType;

    @Column(name = "actor_id")
    private java.util.UUID actorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private java.util.Map<String, Object> details;

    @Column(name = "request_key", columnDefinition = "text")
    private String requestKey;
}




