package com.skylane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** skylane.service-area.* — design.md D23: San Francisco. */
@ConfigurationProperties(prefix = "skylane.service-area")
public record ServiceAreaProperties(
        @DefaultValue("37.70") double minLat,
        @DefaultValue("37.84") double maxLat,
        @DefaultValue("-122.52") double minLng,
        @DefaultValue("-122.35") double maxLng) {

    public boolean contains(double lat, double lng) {
        return lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
    }
}
