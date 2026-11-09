package com.skylane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;

/** skylane.booking.* — design 8.4 (D39, D40). serviceHours is local time in {@code zone}. */
@ConfigurationProperties(prefix = "skylane.booking")
public record BookingProperties(
        @DefaultValue("7") int maxDaysAhead,
        @DefaultValue("0.2") double bufferRatio,
        @DefaultValue("5") int minBufferMinutes,
        @DefaultValue("08:00-20:00") String serviceHours,
        @DefaultValue("America/Los_Angeles") String zone) {

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }

    public LocalTime opensAt() {
        return LocalTime.parse(serviceHours.split("-")[0].trim());
    }

    public LocalTime closesAt() {
        return LocalTime.parse(serviceHours.split("-")[1].trim());
    }

    /** D40: the whole occupation (outbound + return) times 1 + bufferRatio, and at least minBufferMinutes extra. */
    public Duration buffer(Duration occupation) {
        long ratioMs = Math.round(occupation.toMillis() * bufferRatio);
        return Duration.ofMillis(Math.max(ratioMs, Duration.ofMinutes(minBufferMinutes).toMillis()));
    }
}
