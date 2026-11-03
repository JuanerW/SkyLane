package com.skylane.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One Agent chat (design.md 8.2). */
@Entity
@Table(name = "conversation", indexes = @Index(name = "conversation_user_idx", columnList = "user_id, created_at"))
@Getter @Setter @NoArgsConstructor
public class Conversation extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;
}
