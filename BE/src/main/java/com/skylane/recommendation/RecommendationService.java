package com.skylane.recommendation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skylane.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
@Profile("!memory")
public class RecommendationService {
    public record Response(UUID optionId, String reason, Map<String, Object> factors) {}
    private record Quote(Instant expiresAt, String notes, double lat, double lng) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final WindClient weather;
    private final double windThreshold;
    private final ReasonWriter reasons;
    public RecommendationService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock, WindClient weather,
                                 @Value("${skylane.recommendation.wind-threshold-mps:10}") double windThreshold,
                                 ReasonWriter reasons) {
        this.jdbc = jdbc; this.mapper = mapper; this.clock = clock; this.weather = weather; this.reasons = reasons;
        if (!Double.isFinite(windThreshold) || windThreshold <= 0) throw new IllegalArgumentException("Wind threshold must be positive");
        this.windThreshold = windThreshold;
    }
    @Transactional
    public Response recommend(UUID userId, UUID quoteId) {
        // Lock the owned quote to serialize concurrent requests, including the first cache insertion.
        List<Quote> quotes = jdbc.query("""
                SELECT expires_at, notes, ST_Y(pickup_location::geometry) AS lat,
                       ST_X(pickup_location::geometry) AS lng
                FROM quote WHERE id = ? AND user_id = ? FOR UPDATE
                """, (rs, i) -> new Quote(rs.getTimestamp("expires_at").toInstant(), rs.getString("notes"),
                rs.getDouble("lat"), rs.getDouble("lng")), quoteId, userId);
        if (quotes.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "QUOTE_NOT_FOUND", "Quote not found");
        Quote quote = quotes.getFirst();
        Instant now = clock.instant();
        if (!quote.expiresAt().isAfter(now)) throw new ApiException(HttpStatus.CONFLICT, "QUOTE_EXPIRED", "Please refresh delivery options");
        List<Response> cached = jdbc.query("SELECT option_id, reason, factors::text FROM recommendation WHERE quote_id = ?",
                (rs, i) -> new Response(rs.getObject("option_id", UUID.class), rs.getString("reason"), factors(rs.getString("factors"))), quoteId);
        if (!cached.isEmpty()) return cached.getFirst();
        List<RecommendationRules.Option> options = jdbc.query("""
                SELECT id, device_type, price_cents, departs_at, eta_at FROM quote_option
                WHERE quote_id = ? AND available = true AND price_cents IS NOT NULL
                  AND departs_at IS NOT NULL AND eta_at IS NOT NULL
                """, (rs, i) -> new RecommendationRules.Option(rs.getObject("id", UUID.class), rs.getString("device_type"),
                rs.getInt("price_cents"), rs.getTimestamp("departs_at").toInstant(), rs.getTimestamp("eta_at").toInstant()), quoteId);
        if (options.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "NO_AVAILABLE_OPTIONS", "No delivery option is available");
        var result = RecommendationRules.choose(options, quote.notes(), now, weather.currentWind(quote.lat(), quote.lng()), windThreshold);
        // D34: DeepSeek may reword the reason; on any failure the rule text is kept and model stays NULL.
        Optional<String> llm = reasons.write(result.reason(), result.factors(), quote.notes());
        String reason = llm.orElse(result.reason());
        jdbc.update("INSERT INTO recommendation (quote_id, option_id, reason, factors, model) VALUES (?, ?, ?, ?::jsonb, ?)",
                quoteId, result.optionId(), reason, json(result.factors()), llm.isPresent() ? reasons.model() : null);
        return new Response(result.optionId(), reason, result.factors());
    }
    private Map<String, Object> factors(String value) {
        try { return mapper.readValue(value, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored recommendation factors", e); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize recommendation", e); }
    }
}
