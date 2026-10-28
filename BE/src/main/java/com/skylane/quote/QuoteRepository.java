package com.skylane.quote;

import com.skylane.geo.LatLng;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface QuoteRepository {

    /** One option plus the two leg lengths that quote_option stores next to distance_m. */
    record OptionRow(QuoteResponse.Option option, int leg1M, int leg2M) {
    }

    record NewQuote(UUID id, UUID userId, QuoteRequest request, BigDecimal weightKg, LatLng pickup, LatLng dropoff,
                    Instant deadline, Instant expiresAt, List<OptionRow> options) {
    }

    /** Saves the quote and its option rows in one transaction. */
    void save(NewQuote quote);
}
