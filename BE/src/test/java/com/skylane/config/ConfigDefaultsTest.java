package com.skylane.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A teammate's application.yml may not have our keys: every property must still have a safe default. */
class ConfigDefaultsTest {
    private final Binder empty = new Binder(new MapConfigurationPropertySource(Map.of()));

    @Test
    void missingKeysFallBackToDefaults() {
        PricingProperties pricing = empty.bindOrCreate("skylane.pricing", PricingProperties.class);
        assertThat(pricing.usdPerKgMile()).isEqualByComparingTo("1.00");
        assertThat(pricing.minCents()).isEqualTo(100);
        assertThat(empty.bindOrCreate("skylane.quote", QuoteProperties.class).ttlMinutes()).isEqualTo(10);
        assertThat(empty.bindOrCreate("skylane.service-area", ServiceAreaProperties.class).contains(37.7955, -122.3937)).isTrue();
        assertThat(empty.bindOrCreate("skylane.routing", RoutingProperties.class).fallbackFactor()).isEqualTo(1.3);
    }

    @Test
    void designMdKeyLayoutBinds() {
        var src = new MapConfigurationPropertySource(Map.of(
                "skylane.pricing.usd-per-kg-mile", "2.00",
                "skylane.quote.ttl-minutes", "5",
                "skylane.service-area.max-lat", "37.80"));
        Binder b = new Binder(src);
        assertThat(b.bindOrCreate("skylane.pricing", PricingProperties.class).usdPerKgMile()).isEqualByComparingTo("2.00");
        assertThat(b.bindOrCreate("skylane.quote", QuoteProperties.class).ttlMinutes()).isEqualTo(5);
        assertThat(b.bindOrCreate("skylane.service-area", ServiceAreaProperties.class).maxLat()).isEqualTo(37.80);
    }
}
