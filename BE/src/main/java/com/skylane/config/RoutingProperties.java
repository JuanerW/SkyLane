package com.skylane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** skylane.routing.* — design.md has provider / ors-api-key / osrm-base-url; osm-base-url is for routing.openstreetmap.de. */
@ConfigurationProperties(prefix = "skylane.routing")
public record RoutingProperties(
        @DefaultValue("https://routing.openstreetmap.de/routed-foot/route/v1/foot") String osmBaseUrl,
        @DefaultValue("3000") int timeoutMs,
        @DefaultValue("1.3") double fallbackFactor) {
}
