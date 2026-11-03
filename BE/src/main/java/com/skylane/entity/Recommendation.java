package com.skylane.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Recommended option of a quote; rules pick it, the LLM only writes the reason (design.md D34). */
@Entity
@Table(name = "recommendation")
@Getter @Setter @NoArgsConstructor
public class Recommendation extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false, unique = true)
    private Quote quote;

    @Column(name = "option_id", nullable = false)
    private java.util.UUID optionId;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private java.util.Map<String, Object> factors;

    /** Null = rules only, no LLM. */
    @Column(columnDefinition = "text")
    private String model;
}
