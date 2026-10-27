package com.skylane.repository;

import com.skylane.entity.Quote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuoteRepository extends JpaRepository<Quote, UUID> {
    Optional<Quote> findByIdAndUser_Id(UUID id, UUID userId);
    List<Quote> findByUser_IdOrderByCreatedAtDesc(UUID userId);
}
