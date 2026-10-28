package com.skylane.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "quote_option", uniqueConstraints = @UniqueConstraint(name = "quote_option_quote_uq", columnNames = {"quote_id", "id"}))
@Getter @Setter @NoArgsConstructor
public class QuoteOption extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false)
    private Quote quote;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    @Convert(converter = DeviceTypeConverter.class)
    @Column(name = "device_type", nullable = false, columnDefinition = "text")
    private DeviceType deviceType;

    @Column(nullable = false)
    private boolean available;

    @Enumerated(EnumType.STRING)
    @Column(name = "unavailable_reason", columnDefinition = "text")
    private UnavailableReason unavailableReason;

    @Column(name = "departs_at")
    private Instant departsAt;

    @Column(name = "eta_at")
    private Instant etaAt;

    @Column(name = "price_cents")
    private Integer priceCents;

    @Column(name = "distance_m")
    private Integer distanceM;

    @Column(name = "leg1_m")
    private Integer leg1M;

    @Column(name = "leg2_m")
    private Integer leg2M;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "route_points", columnDefinition = "jsonb")
    private java.util.List<java.util.List<Double>> routePoints;

    @Column(nullable = false, columnDefinition = "text[]")
    private String[] tags = new String[0];
}



