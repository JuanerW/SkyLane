package com.skylane.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** An Agent proposal that only runs after the user confirms it (design.md D36). */
@Entity
@Table(name = "agent_action", indexes = @Index(name = "agent_action_conv_idx", columnList = "conversation_id, created_at"))
@Getter @Setter @NoArgsConstructor
public class AgentAction extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    /** place_order / cancel_order */
    @Column(nullable = false, columnDefinition = "text")
    private String kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private java.util.Map<String, Object> payload;

    /** pending / confirmed / rejected / expired / failed */
    @Column(nullable = false, columnDefinition = "text")
    private String status = "pending";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private java.util.Map<String, Object> result;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
