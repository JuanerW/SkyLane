package com.skylane.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skylane.config.RoutingProperties;
import com.skylane.geo.Geo;
import com.skylane.geo.LatLng;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class OsmRoutingClient implements RoutingClient {
    private static final Logger log = LoggerFactory.getLogger(OsmRoutingClient.class);

    private final RoutingProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OsmRoutingClient(RoutingProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(props.timeoutMs())).build();
    }

    @Override
    public RoutePlan walkingRoute(LatLng station, LatLng pickup, LatLng dropoff) {
        try {
            String coords = String.format(Locale.ROOT, "%f,%f;%f,%f;%f,%f",
                    station.lng(), station.lat(), pickup.lng(), pickup.lat(), dropoff.lng(), dropoff.lat());
            URI uri = URI.create(props.osmBaseUrl() + "/" + coords + "?overview=full&geometries=geojson");
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(props.timeoutMs())).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IllegalStateException("routing HTTP " + res.statusCode());
            }
            JsonNode route = mapper.readTree(res.body()).path("routes").path(0);
            JsonNode legs = route.path("legs");
            if (legs.size() != 2) {
                throw new IllegalStateException("unexpected routing response");
            }
            List<LatLng> points = new ArrayList<>();
            for (JsonNode c : route.path("geometry").path("coordinates")) {
                points.add(new LatLng(c.get(1).asDouble(), c.get(0).asDouble()));
            }
            return new RoutePlan(points, legs.get(0).path("distance").asDouble(), legs.get(1).path("distance").asDouble());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Routing failed ({}), falling back to straight line x{}", e.getMessage(), props.fallbackFactor());
            return fallback(station, pickup, dropoff, props.fallbackFactor());
        }
    }

    public static RoutePlan fallback(LatLng station, LatLng pickup, LatLng dropoff, double factor) {
        return new RoutePlan(List.of(station, pickup, dropoff),
                Geo.haversineM(station, pickup) * factor,
                Geo.haversineM(pickup, dropoff) * factor);
    }
}
