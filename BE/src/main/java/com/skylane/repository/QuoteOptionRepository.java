package com.skylane.repository;

import com.skylane.entity.QuoteOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuoteOptionRepository extends JpaRepository<QuoteOption, UUID> {
    List<QuoteOption> findByQuote_IdOrderByCreatedAtAsc(UUID quoteId);
    List<QuoteOption> findByQuote_IdAndAvailableTrue(UUID quoteId);
    Optional<QuoteOption> findByIdAndQuote_Id(UUID id, UUID quoteId);
}
