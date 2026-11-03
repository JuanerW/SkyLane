package com.skylane.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A company that runs robots or drones on SkyLane (design.md 8.1). */
@Entity
@Table(name = "operator")
@Getter @Setter @NoArgsConstructor
public class Operator extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    @Column(nullable = false, unique = true, columnDefinition = "text")
    private String name;
}
