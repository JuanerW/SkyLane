package com.skylane.quote;

import java.time.Instant;
import java.util.List;

public record QuoteResponse(String quoteId, Instant expiresAt, List<Option> options) {

    public record StationRef(String id, String name, double lat, double lng) {
    }

    public record Option(
            String optionId,
            StationRef station,
            String deviceType,
            boolean available,
            String unavailableReason,
            Instant departsAt,
            Instant etaAt,
            Integer priceCents,
            Integer distanceM,
            List<double[]> routePoints,
            List<String> tags) {
    }
}
