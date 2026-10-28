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
@Table(name = "quote")
@Getter @Setter @NoArgsConstructor
public class Quote extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "item_description", nullable = false, columnDefinition = "text")
    private String itemDescription;

    @Column(name = "is_fragile", nullable = false)
    private boolean fragile;

    @Column(name = "weight_kg", nullable = false, precision = 5, scale = 2)
    private BigDecimal weightKg;

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

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "algorithm_version", columnDefinition = "text")
    private String algorithmVersion;
}


