package com.skylane.quote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
@Profile("!memory")
public class JdbcQuoteRepository implements QuoteRepository {
    static final String ALGORITHM_VERSION = "v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcQuoteRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void save(NewQuote q) {
        QuoteRequest r = q.request();
        jdbc.update("""
                INSERT INTO quote (id, user_id, item_description, is_fragile, weight_kg, notes,
                                   pickup_text, pickup_location, dropoff_text, dropoff_location,
                                   deadline_at, expires_at, algorithm_version)
                VALUES (?, ?, ?, ?, ?, ?,
                        ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                        ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                        ?, ?, ?)""",
                q.id(), q.userId(), r.item().description(), r.item().fragile(), q.weightKg(), r.notes(),
                r.pickup().text(), q.pickup().lng(), q.pickup().lat(),
                r.dropoff().text(), q.dropoff().lng(), q.dropoff().lat(),
                utc(q.deadline()), utc(q.expiresAt()), ALGORITHM_VERSION);

        for (OptionRow row : q.options()) {
            QuoteResponse.Option o = row.option();
            jdbc.update("""
                    INSERT INTO quote_option (id, quote_id, station_id, device_type, available, unavailable_reason,
                                              departs_at, eta_at, price_cents, distance_m, leg1_m, leg2_m,
                                              route_points, tags)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::text[])""",
                    UUID.fromString(o.optionId()), q.id(), UUID.fromString(o.station().id()), o.deviceType(),
                    o.available(), o.unavailableReason(), utc(o.departsAt()), utc(o.etaAt()), o.priceCents(),
                    o.distanceM(), row.leg1M(), row.leg2M(), json(o), "{" + String.join(",", o.tags()) + "}");
        }
    }

    private String json(QuoteResponse.Option o) {
        try {
            return mapper.writeValueAsString(o.routePoints());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime utc(Instant t) {
        return t == null ? null : OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
