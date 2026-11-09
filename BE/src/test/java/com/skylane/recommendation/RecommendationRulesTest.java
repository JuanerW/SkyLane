package com.skylane.recommendation;

import com.skylane.common.ApiException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RecommendationRulesTest {
    private final Instant now = Instant.parse("2026-11-08T12:00:00Z");
    private RecommendationRules.Option option(String type, int price, int wait, int arrival) {
        return new RecommendationRules.Option(UUID.randomUUID(), type, price, now.plusSeconds(wait), now.plusSeconds(arrival));
    }
    @Test void budgetAndUrgencyChooseDifferentOptions() {
        var cheap = option("robot", 100, 0, 600);
        var fast = option("drone", 300, 0, 60);
        assertThat(RecommendationRules.choose(List.of(fast, cheap), "便宜一点", now, null, 10).optionId()).isEqualTo(cheap.id());
        assertThat(RecommendationRules.choose(List.of(fast, cheap), "urgent please", now, null, 10).optionId()).isEqualTo(fast.id());
    }
    @Test void windPenalizesDroneWhenOtherwiseEqual() {
        var robot = option("robot", 100, 0, 60);
        var drone = option("drone", 100, 0, 60);
        assertThat(RecommendationRules.choose(List.of(drone, robot), "", now, 12.0, 10).optionId()).isEqualTo(robot.id());
    }
    @Test void noWeatherStillProducesRecommendationAndExplainsFallback() {
        var result = RecommendationRules.choose(List.of(option("robot", 100, 0, 60)), "", now, null, 10);
        assertThat(result.factors()).containsEntry("weather_status", "unavailable");
        assertThat(result.reason()).contains("wind data is unavailable");
    }
    @Test void queuedOptionLosesWhenPriceAndArrivalAreEqual() {
        var ready = option("robot", 100, 0, 600);
        assertThat(RecommendationRules.choose(List.of(option("robot", 100, 300, 600), ready), "", now, 0.0, 10).optionId()).isEqualTo(ready.id());
    }
    @Test void rejectsEmptyOptionsAndTiesAreStable() {
        assertThatThrownBy(() -> RecommendationRules.choose(List.of(), "", now, null, 10)).isInstanceOf(ApiException.class);
        var a = option("robot", 100, 0, 60); var b = option("robot", 100, 0, 60);
        assertThat(RecommendationRules.choose(List.of(a,b), "", now, null, 10).optionId())
                .isEqualTo(RecommendationRules.choose(List.of(b,a), "", now, null, 10).optionId());
    }
}
