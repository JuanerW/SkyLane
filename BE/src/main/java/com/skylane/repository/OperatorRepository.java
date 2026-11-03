package com.skylane.repository;

import com.skylane.entity.Operator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OperatorRepository extends JpaRepository<Operator, UUID> {
    java.util.Optional<Operator> findByName(String name);
}
