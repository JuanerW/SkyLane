package com.skylane.recommendation;

import com.skylane.common.ApiException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.*;

/** Pure scoring: unavailable options never participate. Text is data, never an instruction. */
public final class RecommendationRules {
    public record Option(UUID id, String deviceType, int priceCents, Instant departsAt, Instant etaAt) {}
    public record Result(UUID optionId, String reason, Map<String, Object> factors) {}

    public static Result choose(List<Option> options, String notes, Instant now, Double windMps, double windThreshold) {
        if (options.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "NO_AVAILABLE_OPTIONS", "No delivery option is available");
        String text = notes == null ? "" : notes.toLowerCase(Locale.ROOT);
        boolean cheap = contains(text, "cheap", "budget", "lowest cost", "便宜", "省钱", "低价");
        boolean fast = contains(text, "urgent", "fast", "as soon", "尽快", "加急", "最快");
        String preference = cheap == fast ? "balanced" : cheap ? "price" : "speed";
        double pw = preference.equals("price") ? .75 : preference.equals("speed") ? .15 : .40;
        double tw = preference.equals("speed") ? .75 : preference.equals("price") ? .15 : .45;
        double ww = 1 - pw - tw;
        double minPrice = options.stream().mapToInt(Option::priceCents).min().orElseThrow();
        double maxPrice = options.stream().mapToInt(Option::priceCents).max().orElseThrow();
        double minEta = options.stream().mapToLong(o -> o.etaAt().toEpochMilli()).min().orElseThrow();
        double maxEta = options.stream().mapToLong(o -> o.etaAt().toEpochMilli()).max().orElseThrow();
        double minWait = options.stream().mapToDouble(o -> waitSeconds(o, now)).min().orElseThrow();
        double maxWait = options.stream().mapToDouble(o -> waitSeconds(o, now)).max().orElseThrow();
        boolean windy = windMps != null && windMps >= windThreshold;
        Map<UUID, Double> scores = new HashMap<>();
        for (Option o : options) {
            double score = pw * norm(o.priceCents(), minPrice, maxPrice)
                    + tw * norm(o.etaAt().toEpochMilli(), minEta, maxEta)
                    + ww * norm(waitSeconds(o, now), minWait, maxWait)
                    + (windy && o.deviceType().equals("drone") ? .35 : 0);
            scores.put(o.id(), score);
        }
        Option best = options.stream().min(Comparator.comparingDouble((Option o) -> scores.get(o.id()))
                .thenComparing(Option::etaAt).thenComparingInt(Option::priceCents).thenComparing(o -> o.id().toString())).orElseThrow();
        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("algorithm", "rules-v1");
        factors.put("preference", preference);
        factors.put("price_cents", best.priceCents());
        factors.put("eta_at", best.etaAt().toString());
        factors.put("queue_seconds", Math.round(waitSeconds(best, now)));
        factors.put("weights", Map.of("price", pw, "arrival", tw, "queue", ww));
        factors.put("weather_status", windMps == null ? "unavailable" : "available");
        if (windMps != null) factors.put("wind_speed_mps", windMps);
        factors.put("wind_threshold_mps", windThreshold);
        factors.put("drone_wind_penalty", windy ? .35 : 0);
        String reason = switch (preference) {
            case "price" -> "Recommended for your budget preference, balancing price with arrival and queue time.";
            case "speed" -> "Recommended for your speed preference, balancing arrival and queue time with price.";
            default -> "Recommended for its balance of price, arrival time and time waiting to depart.";
        };
        if (windy) reason += " Current wind lowers the ranking of drone options.";
        if (windMps == null) reason += " Live wind data is unavailable; this recommendation uses the delivery quote.";
        return new Result(best.id(), reason, factors);
    }
    private static double waitSeconds(Option o, Instant now) { return Math.max(0, (o.departsAt().toEpochMilli() - now.toEpochMilli()) / 1000.0); }
    private static double norm(double x, double min, double max) { return max == min ? 0 : (x - min) / (max - min); }
    private static boolean contains(String text, String... words) { return Arrays.stream(words).anyMatch(text::contains); }
    private RecommendationRules() {}
}
