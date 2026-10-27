package com.skylane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** skylane.quote.* — shares the prefix with design.md's quote: {ttl-minutes, stale-tolerance-minutes}. */
@ConfigurationProperties(prefix = "skylane.quote")
public record QuoteProperties(
        @DefaultValue("10") int ttlMinutes,
        @DefaultValue("2") double droneMaxKg,
        @DefaultValue("10") double robotMaxKg,
        @DefaultValue("15") double droneMaxRoundTripKm,
        @DefaultValue("1.5") double robotSpeedMps,
        @DefaultValue("15") double droneSpeedMps,
        @DefaultValue("1") int handlingMinutes,
        @DefaultValue("2") int droneOverheadMinutes) {
}
