package com.skylane.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** D42: earliest start at or after {@code from} where a slot of {@code length} fits between busy intervals. */
public final class Gaps {
    public record Interval(Instant start, Instant end) {
    }

    private Gaps() {
    }

    /** busy must be sorted by start; intervals may overlap or end before {@code from}. */
    public static Instant earliestStart(List<Interval> busy, Instant from, Duration length) {
        Instant cursor = from;
        for (Interval b : busy) {
            if (!b.end().isAfter(cursor)) {
                continue;
            }
            if (!cursor.plus(length).isAfter(b.start())) {
                return cursor;
            }
            cursor = b.end();
        }
        return cursor;
    }
}
