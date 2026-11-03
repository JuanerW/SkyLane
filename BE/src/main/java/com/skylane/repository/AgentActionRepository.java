package com.skylane.repository;

import com.skylane.entity.AgentAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AgentActionRepository extends JpaRepository<AgentAction, UUID> {
}
