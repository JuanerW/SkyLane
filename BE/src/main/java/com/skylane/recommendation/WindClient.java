package com.skylane.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Locale;

@Component
public class WindClient {
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public WindClient(ObjectMapper mapper, @Value("${skylane.recommendation.weather-enabled:true}") boolean enabled) {
        this.mapper = mapper; this.enabled = enabled;
    }
    /** Best effort, metres/second. Failure must not prevent a recommendation or order. */
    public Double currentWind(double lat, double lng) {
        if (!enabled) return null;
        try {
            URI uri = URI.create(String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.6f&longitude=%.6f&current=wind_speed_10m&wind_speed_unit=ms", lat, lng));
            var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            var value = mapper.readTree(response.body()).path("current").path("wind_speed_10m");
            double speed = value.asDouble(-1);
            return value.isNumber() && Double.isFinite(speed) && speed >= 0 ? speed : null;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }
}
