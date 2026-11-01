package com.skylane.quote;

import com.skylane.common.ApiException;
import com.skylane.config.PricingProperties;
import com.skylane.config.QuoteProperties;
import com.skylane.config.ServiceAreaProperties;
import com.skylane.geo.Geo;
import com.skylane.geo.LatLng;
import com.skylane.geo.SteepZones;
import com.skylane.routing.RoutePlan;
import com.skylane.routing.RoutingClient;
import com.skylane.station.Device;
import com.skylane.station.DeviceType;
import com.skylane.station.Station;
import com.skylane.station.StationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

@Service
public class QuoteService {
    private static final double METERS_PER_MILE = 1609.344;

    private final StationRepository stations;
    private final RoutingClient routing;
    private final QuoteProperties props;
    private final PricingProperties pricing;
    private final ServiceAreaProperties area;
    private final QuoteRepository quotes;
    private final Clock clock;

    public QuoteService(StationRepository stations, RoutingClient routing, QuoteProperties props,
                        PricingProperties pricing, ServiceAreaProperties area, QuoteRepository quotes, Clock clock) {
        this.stations = stations;
        this.routing = routing;
        this.props = props;
        this.pricing = pricing;
        this.area = area;
        this.quotes = quotes;
        this.clock = clock;
    }

    public QuoteResponse createQuote(UUID userId, QuoteRequest req) {
        LatLng pickup = new LatLng(req.pickup().lat(), req.pickup().lng());
        LatLng dropoff = new LatLng(req.dropoff().lat(), req.dropoff().lng());
        if (!area.contains(pickup.lat(), pickup.lng()) || !area.contains(dropoff.lat(), dropoff.lng())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OUT_OF_SERVICE_AREA", "Address is outside the San Francisco service area");
        }
        if (Geo.haversineM(pickup, dropoff) < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_ADDRESS", "Pickup and drop-off are the same address");
        }

        Instant now = clock.instant();
        Instant deadline = req.arriveWithinMinutes() == null ? null : now.plus(Duration.ofMinutes(req.arriveWithinMinutes()));
        // quote.weight_kg is numeric(5,2): round once so the price, the checks and the stored value all agree
        BigDecimal weight = BigDecimal.valueOf(req.weightKg()).setScale(2, RoundingMode.HALF_UP);

        List<Station> activeStations = stations.findActiveStations();
        if (activeStations.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_STATIONS", "No active stations are available");
        }
        List<RoutePlan> robotRoutes = walkingRoutes(activeStations, pickup, dropoff);

        List<QuoteRepository.OptionRow> rows = new ArrayList<>();
        for (int i = 0; i < activeStations.size(); i++) {
            Station station = activeStations.get(i);
            RoutePlan droneRoute = new RoutePlan(List.of(station.location(), pickup, dropoff),
                    Geo.haversineM(station.location(), pickup), Geo.haversineM(pickup, dropoff));
            rows.add(buildOption(station, DeviceType.ROBOT, robotRoutes.get(i), weight, now, deadline));
            rows.add(buildOption(station, DeviceType.DRONE, droneRoute, weight, now, deadline));
        }
        List<QuoteResponse.Option> tagged = applyTags(rows.stream().map(QuoteRepository.OptionRow::option).toList());
        List<QuoteRepository.OptionRow> taggedRows = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            taggedRows.add(new QuoteRepository.OptionRow(tagged.get(i), rows.get(i).leg1M(), rows.get(i).leg2M()));
        }

        UUID quoteId = UUID.randomUUID();
        Instant expiresAt = now.plus(Duration.ofMinutes(props.ttlMinutes()));
        quotes.save(new QuoteRepository.NewQuote(quoteId, userId, req, weight, pickup, dropoff, deadline, expiresAt, taggedRows));
        return new QuoteResponse(quoteId.toString(), expiresAt, tagged);
    }

    /** One routing call per station, run in parallel so a slow routing server costs one timeout, not three. */
    private List<RoutePlan> walkingRoutes(List<Station> active, LatLng pickup, LatLng dropoff) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<RoutePlan>> futures = active.stream()
                    .map(s -> CompletableFuture.supplyAsync(() -> routing.walkingRoute(s.location(), pickup, dropoff), executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        }
    }

    private QuoteRepository.OptionRow buildOption(Station station, DeviceType type, RoutePlan route, BigDecimal weight,
                                             Instant now, Instant deadline) {
        String reason = physicalReason(station, type, route, weight.doubleValue());
        Integer distance = (int) Math.round(route.totalM());
        List<double[]> points = route.points().stream().map(p -> new double[]{p.lat(), p.lng()}).toList();
        QuoteResponse.StationRef ref = new QuoteResponse.StationRef(station.id().toString(), station.name(),
                station.location().lat(), station.location().lng());
        String optionId = UUID.randomUUID().toString();

        if (reason != null) {
            return row(new QuoteResponse.Option(optionId, ref, type.json(), false, reason, null, null, null,
                    distance, points, List.of()), route);
        }

        List<Device> devices = stations.findActiveDevices(station, type);
        Instant departs = devices.stream()
                .map(d -> d.availableAt() == null || d.availableAt().isBefore(now) ? now : d.availableAt())
                .min(Comparator.naturalOrder()).orElse(now);
        long travelSeconds = Math.round(route.totalM() / (type == DeviceType.ROBOT ? props.robotSpeedMps() : props.droneSpeedMps()));
        long extraMinutes = props.handlingMinutes() + (type == DeviceType.DRONE ? props.droneOverheadMinutes() : 0);
        Instant eta = departs.plusSeconds(travelSeconds).plus(Duration.ofMinutes(extraMinutes));
        int price = priceCents(weight, route.leg2M(), type);

        if (devices.isEmpty()) {
            reason = "NO_DEVICE_AVAILABLE";
        } else if (deadline != null && eta.isAfter(deadline)) {
            reason = "DEADLINE_MISSED";
        }
        return row(new QuoteResponse.Option(optionId, ref, type.json(), reason == null, reason, departs, eta, price,
                distance, points, List.of()), route);
    }

    private static QuoteRepository.OptionRow row(QuoteResponse.Option option, RoutePlan route) {
        return new QuoteRepository.OptionRow(option, (int) Math.round(route.leg1M()), (int) Math.round(route.leg2M()));
    }

    private String physicalReason(Station station, DeviceType type, RoutePlan route, double weightKg) {
        if (weightKg > (type == DeviceType.ROBOT ? props.robotMaxKg() : props.droneMaxKg())) {
            return "OVERWEIGHT";
        }
        if (type == DeviceType.ROBOT && SteepZones.touchedBy(route.points())) {
            return "STEEP_TERRAIN";
        }
        if (type == DeviceType.DRONE) {
            LatLng drop = route.points().get(2);
            double roundTrip = route.totalM() + Geo.haversineM(drop, station.location());
            if (roundTrip > props.droneMaxRoundTripKm() * 1000) {
                return "OUT_OF_RANGE";
            }
        }
        return null;
    }

    /** weight kg x pickup-to-dropoff miles x $1, minimum $1, rounded to the cent. */
    int priceCents(BigDecimal weightKg, double leg2M, DeviceType type) {
        BigDecimal miles = BigDecimal.valueOf(leg2M).divide(BigDecimal.valueOf(METERS_PER_MILE), 8, RoundingMode.HALF_UP);
        int cents = weightKg.multiply(miles).multiply(pricing.usdPerKgMile())
                .movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
        return Math.max(cents, pricing.minCents());
    }

    /** fastest = earliest arrival, cheapest = lowest price, recommended = min of 0.6 x time + 0.4 x price (both normalised). */
    private List<QuoteResponse.Option> applyTags(List<QuoteResponse.Option> options) {
        List<QuoteResponse.Option> ok = options.stream().filter(QuoteResponse.Option::available).toList();
        if (ok.isEmpty()) {
            return options;
        }
        long minEta = ok.stream().mapToLong(o -> o.etaAt().toEpochMilli()).min().getAsLong();
        long maxEta = ok.stream().mapToLong(o -> o.etaAt().toEpochMilli()).max().getAsLong();
        int minPrice = ok.stream().mapToInt(QuoteResponse.Option::priceCents).min().getAsInt();
        int maxPrice = ok.stream().mapToInt(QuoteResponse.Option::priceCents).max().getAsInt();

        QuoteResponse.Option fastest = ok.stream().min(Comparator.comparing(QuoteResponse.Option::etaAt)
                .thenComparing(QuoteResponse.Option::priceCents)).get();
        QuoteResponse.Option cheapest = ok.stream().min(Comparator.comparingInt(QuoteResponse.Option::priceCents)
                .thenComparing(QuoteResponse.Option::etaAt)).get();
        QuoteResponse.Option recommended = ok.stream().min(Comparator.comparingDouble((QuoteResponse.Option o) ->
                0.6 * norm(o.etaAt().toEpochMilli(), minEta, maxEta) + 0.4 * norm(o.priceCents(), minPrice, maxPrice))).get();

        return options.stream().map(o -> {
            List<String> tags = new ArrayList<>();
            if (o == fastest) tags.add("fastest");
            if (o == cheapest) tags.add("cheapest");
            if (o == recommended) tags.add("recommended");
            return tags.isEmpty() ? o : new QuoteResponse.Option(o.optionId(), o.station(), o.deviceType(), o.available(),
                    o.unavailableReason(), o.departsAt(), o.etaAt(), o.priceCents(), o.distanceM(), o.routePoints(), tags);
        }).toList();
    }

    private static double norm(double v, double min, double max) {
        return max == min ? 0 : (v - min) / (max - min);
    }
}
