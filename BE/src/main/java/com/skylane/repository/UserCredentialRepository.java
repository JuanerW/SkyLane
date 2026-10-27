package com.skylane.repository;

import com.skylane.entity.UserCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserCredentialRepository extends JpaRepository<UserCredential, UUID> {
    Optional<UserCredential> findByUserId(UUID userId);
    boolean existsByUserId(UUID userId);
}
