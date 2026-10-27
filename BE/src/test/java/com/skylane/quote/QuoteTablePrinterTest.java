package com.skylane.quote;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteTablePrinterTest {
    @Test
    void formatsAvailableAndUnavailableOptions() {
        var station = new QuoteResponse.StationRef("s1", "SoMa Station", 37.7785, -122.395);
        var ok = new QuoteResponse.Option("o1", station, "drone", true, null, Instant.parse("2026-10-24T19:00:00Z"),
                Instant.parse("2026-10-24T19:07:00Z"), 179, 3812, List.of(), List.of("fastest", "cheapest"));
        var bad = new QuoteResponse.Option("o2", station, "robot", false, "STEEP_TERRAIN", null, null, null, 6980,
                List.of(), List.of());
        String table = QuoteTablePrinter.format(new QuoteResponse("12345678-aaaa", Instant.parse("2026-10-24T19:10:00Z"), List.of(ok, bad)));
        assertThat(table).contains("Station", "SoMa", "$1.79", "3.8 km", "fastest, cheapest", "STEEP_TERRAIN", "12:07");
        assertThat(table.lines().filter(l -> l.startsWith("SoMa")).count()).isEqualTo(2);
    }
}
