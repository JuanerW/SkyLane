package com.skylane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/** skylane.pricing.* — design.md: pricing: {usd-per-kg-mile: 1.00, min-cents: 100}. */
@ConfigurationProperties(prefix = "skylane.pricing")
public record PricingProperties(
        @DefaultValue("1.00") BigDecimal usdPerKgMile,
        @DefaultValue("100") int minCents) {
}
