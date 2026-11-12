package com.skylane.booking;

import com.skylane.common.ApiException;
import com.skylane.config.BookingProperties;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;

/** D39: pickup_after must be in the future (small clock skew allowed), at most maxDaysAhead out, inside service hours. */
public final class PickupTime {
    private static final Duration SKEW = Duration.ofMinutes(1);

    private PickupTime() {
    }

    /** Returns the instant the device may start, i.e. max(now, pickupAfter). */
    public static Instant validate(Instant pickupAfter, Instant now, BookingProperties booking) {
        if (pickupAfter == null) {
            return now;
        }
        if (pickupAfter.isBefore(now.minus(SKEW))) {
            throw invalid("Pickup time is in the past");
        }
        if (pickupAfter.isAfter(now.plus(Duration.ofDays(booking.maxDaysAhead())))) {
            throw invalid("Pickup time can be at most " + booking.maxDaysAhead() + " days ahead");
        }
        LocalTime local = pickupAfter.atZone(booking.zoneId()).toLocalTime();
        if (local.isBefore(booking.opensAt()) || local.isAfter(booking.closesAt())) {
            throw invalid("Pickup time must be between " + booking.serviceHours());
        }
        return pickupAfter.isAfter(now) ? pickupAfter : now;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "PICKUP_TIME_INVALID", message);
    }
}
