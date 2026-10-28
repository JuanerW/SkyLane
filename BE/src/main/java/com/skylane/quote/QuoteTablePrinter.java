package com.skylane.quote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Dev aid: logs each created quote as a table. Enable with skylane.dev.print-quote-table=true. */
@Component
@ConditionalOnProperty(name = "skylane.dev.print-quote-table", havingValue = "true")
public class QuoteTablePrinter {
    private static final Logger log = LoggerFactory.getLogger(QuoteTablePrinter.class);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("America/Los_Angeles"));

    public void print(QuoteResponse quote) {
        log.info("\n{}", format(quote));
    }

    public static String format(QuoteResponse quote) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Station", "Device", "Available", "Reason", "Departs", "ETA", "Price", "Distance", "Tags"});
        for (QuoteResponse.Option o : quote.options()) {
            rows.add(new String[]{
                    o.station().name().replace(" Station", ""),
                    o.deviceType(),
                    o.available() ? "yes" : "no",
                    o.unavailableReason() == null ? "" : o.unavailableReason(),
                    time(o.departsAt()),
                    time(o.etaAt()),
                    o.priceCents() == null ? "-" : String.format(Locale.ROOT, "$%.2f", o.priceCents() / 100.0),
                    o.distanceM() == null ? "-" : String.format(Locale.ROOT, "%.1f km", o.distanceM() / 1000.0),
                    String.join(", ", o.tags())});
        }
        int[] width = new int[rows.get(0).length];
        for (String[] r : rows) {
            for (int i = 0; i < r.length; i++) {
                width[i] = Math.max(width[i], r[i].length());
            }
        }
        StringBuilder sb = new StringBuilder("Quote ").append(quote.quoteId(), 0, 8)
                .append("  expires ").append(time(quote.expiresAt())).append(" PT\n");
        for (int n = 0; n < rows.size(); n++) {
            sb.append(line(rows.get(n), width)).append('\n');
            if (n == 0) {
                String[] dashes = new String[width.length];
                for (int i = 0; i < width.length; i++) {
                    dashes[i] = "-".repeat(width[i]);
                }
                sb.append(line(dashes, width)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String line(String[] cells, int[] width) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            sb.append(String.format("%-" + width[i] + "s", cells[i])).append(i < cells.length - 1 ? "  " : "");
        }
        return sb.toString().stripTrailing();
    }

    private static String time(Instant t) {
        return t == null ? "-" : TIME.format(t);
    }
}
