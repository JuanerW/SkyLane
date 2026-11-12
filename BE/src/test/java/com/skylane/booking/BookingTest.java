package com.skylane.booking;

import com.skylane.common.ApiException;
import com.skylane.config.BookingProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingTest {
    static final BookingProperties BOOKING = new BookingProperties(7, 0.2, 5, "08:00-20:00", "America/Los_Angeles");
    // 2026-11-09 10:00 in San Francisco (PST, UTC-8)
    static final Instant NOW = Instant.parse("2026-11-09T18:00:00Z");
    static final Instant T0 = Instant.parse("2026-11-09T18:00:00Z");

    static Gaps.Interval at(int fromMin, int toMin) {
        return new Gaps.Interval(T0.plus(Duration.ofMinutes(fromMin)), T0.plus(Duration.ofMinutes(toMin)));
    }

    @Test
    void freeDeviceStartsAtOnce() {
        assertThat(Gaps.earliestStart(List.of(), T0, Duration.ofMinutes(30))).isEqualTo(T0);
    }

    @Test
    void fitsBetweenTwoBookings() {
        var busy = List.of(at(0, 10), at(50, 80));
        assertThat(Gaps.earliestStart(busy, T0, Duration.ofMinutes(40))).isEqualTo(T0.plus(Duration.ofMinutes(10)));
    }

    @Test
    void skipsAGapThatIsTooShort() {
        var busy = List.of(at(0, 10), at(30, 80));
        assertThat(Gaps.earliestStart(busy, T0, Duration.ofMinutes(40))).isEqualTo(T0.plus(Duration.ofMinutes(80)));
    }

    @Test
    void ignoresBlocksThatEndedBeforeFrom() {
        var busy = List.of(at(-60, -30), at(-5, 5));
        assertThat(Gaps.earliestStart(busy, T0, Duration.ofMinutes(10))).isEqualTo(T0.plus(Duration.ofMinutes(5)));
    }

    @Test
    void bufferIsRatioWithAMinimum() {
        assertThat(BOOKING.buffer(Duration.ofMinutes(100))).isEqualTo(Duration.ofMinutes(20));
        assertThat(BOOKING.buffer(Duration.ofMinutes(10))).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void pickupTimeRules() {
        assertThat(PickupTime.validate(null, NOW, BOOKING)).isEqualTo(NOW);
        Instant later = NOW.plus(Duration.ofHours(3)); // 13:00 local
        assertThat(PickupTime.validate(later, NOW, BOOKING)).isEqualTo(later);
        assertCode(NOW.minus(Duration.ofHours(1)));
        assertCode(NOW.plus(Duration.ofDays(8)));
        assertCode(NOW.plus(Duration.ofHours(12))); // 22:00 local
    }

    private static void assertCode(Instant t) {
        assertThatThrownBy(() -> PickupTime.validate(t, NOW, BOOKING))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("PICKUP_TIME_INVALID");
    }
}
