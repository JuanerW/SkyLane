package com.skylane.quote;

import com.skylane.common.ApiException;
import com.skylane.config.PricingProperties;
import com.skylane.config.QuoteProperties;
import com.skylane.config.ServiceAreaProperties;
import com.skylane.routing.OsmRoutingClient;
import com.skylane.station.Device;
import com.skylane.station.DeviceType;
import com.skylane.station.Station;
import com.skylane.station.StationRepository;
import com.skylane.station.InMemoryStationRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuoteServiceTest {
    // Fixed demo addresses (design.md: the demo must show >= 4 viable plans out of 6).
    static final QuoteRequest.Place FERRY = new QuoteRequest.Place("Ferry Building", 37.7955, -122.3937);
    static final QuoteRequest.Place ORACLE = new QuoteRequest.Place("Oracle Park", 37.7786, -122.3893);
    static final QuoteRequest.Place TWIN_PEAKS = new QuoteRequest.Place("Twin Peaks", 37.7544, -122.4477);

    static final UUID USER = UUID.randomUUID();
    final Instant now = Instant.parse("2026-10-17T15:00:00Z");
    QuoteService service;
    QuoteRepository.NewQuote saved;

    @BeforeEach
    void setUp() {
        QuoteProperties props = new QuoteProperties(10, 2, 10, 15, 1.5, 15, 1, 2);
        PricingProperties pricing = new PricingProperties(new BigDecimal("1.00"), 100);
        service = newService(new ServiceAreaProperties(37.70, 37.84, -122.52, -122.35), props, pricing);
    }

    private QuoteService newService(ServiceAreaProperties area, QuoteProperties props, PricingProperties pricing) {
        return new QuoteService(new InMemoryStationRepository(),
                (s, p, d) -> OsmRoutingClient.fallback(s, p, d, 1.3), props, pricing, area, q -> saved = q,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private QuoteRequest req(double kg, QuoteRequest.Place from, QuoteRequest.Place to, Integer within) {
        return new QuoteRequest(new QuoteRequest.Item("Cake", true), kg, from, to, null, within);
    }

    private QuoteResponse.Option find(QuoteResponse q, String station, String type) {
        return q.options().stream()
                .filter(o -> o.station().name().startsWith(station) && o.deviceType().equals(type)).findFirst().orElseThrow();
    }

    @Test
    void demoAddressesGiveSixOptionsWithAtLeastFourViable() {
        QuoteResponse q = service.createQuote(USER, req(1.5, FERRY, ORACLE, null));
        assertThat(q.options()).hasSize(6);
        assertThat(q.options().stream().filter(QuoteResponse.Option::available).count()).isGreaterThanOrEqualTo(4);
        assertThat(q.expiresAt()).isEqualTo(now.plusSeconds(600));
    }

    @Test
    void tagsAreAssignedToAvailableOptionsOnly() {
        QuoteResponse q = service.createQuote(USER, req(1.5, FERRY, ORACLE, null));
        List<String> all = q.options().stream().flatMap(o -> o.tags().stream()).toList();
        assertThat(all).contains("fastest", "cheapest", "recommended");
        assertThat(q.options().stream().filter(o -> !o.available())).allMatch(o -> o.tags().isEmpty());
    }

    @Test
    void droneIsFasterAndNotMoreExpensiveThanRobotOnShortTrip() {
        QuoteResponse q = service.createQuote(USER, req(1.5, FERRY, ORACLE, null));
        assertThat(find(q, "SoMa", "drone").etaAt()).isBefore(find(q, "SoMa", "robot").etaAt());
        assertThat(find(q, "SoMa", "drone").priceCents()).isLessThanOrEqualTo(find(q, "SoMa", "robot").priceCents());
    }

    @Test
    void eachTagGoesToTheRightOption() {
        QuoteResponse q = service.createQuote(USER, req(1.5, FERRY, ORACLE, null));
        var ok = q.options().stream().filter(QuoteResponse.Option::available).toList();
        QuoteResponse.Option fastest = ok.stream().filter(o -> o.tags().contains("fastest")).findFirst().orElseThrow();
        QuoteResponse.Option cheapest = ok.stream().filter(o -> o.tags().contains("cheapest")).findFirst().orElseThrow();
        assertThat(fastest.etaAt()).isEqualTo(ok.stream().map(QuoteResponse.Option::etaAt).min(java.util.Comparator.naturalOrder()).get());
        assertThat(cheapest.priceCents()).isEqualTo(ok.stream().mapToInt(QuoteResponse.Option::priceCents).min().getAsInt());
        assertThat(ok.stream().filter(o -> o.tags().contains("recommended"))).hasSize(1);
    }

    @Test
    void priceIsWeightTimesMilesWithMinimumOneDollar() {
        assertThat(service.priceCents(new BigDecimal("2"), 1609.344, DeviceType.ROBOT)).isEqualTo(200);
        assertThat(service.priceCents(new BigDecimal("1.5"), 1609.344, DeviceType.ROBOT)).isEqualTo(150);
        assertThat(service.priceCents(new BigDecimal("0.1"), 100, DeviceType.ROBOT)).isEqualTo(100);
    }

    @Test
    void droneUsesTheSameRateAsRobot() {
        assertThat(service.priceCents(new BigDecimal("2"), 1609.344, DeviceType.DRONE)).isEqualTo(200);
    }

    @Test
    void overweightForDrones() {
        QuoteResponse q = service.createQuote(USER, req(3, FERRY, ORACLE, null));
        assertThat(q.options().stream().filter(o -> o.deviceType().equals("drone")))
                .allMatch(o -> !o.available() && "OVERWEIGHT".equals(o.unavailableReason()) && o.priceCents() == null);
        assertThat(find(q, "SoMa", "robot").available()).isTrue();
    }

    @Test
    void everythingOverweightAboveRobotLimit() {
        QuoteResponse q = service.createQuote(USER, req(11, FERRY, ORACLE, null));
        assertThat(q.options()).allMatch(o -> !o.available() && "OVERWEIGHT".equals(o.unavailableReason()));
    }

    @Test
    void robotsCannotServeSteepTerrain() {
        QuoteResponse q = service.createQuote(USER, req(1, FERRY, TWIN_PEAKS, null));
        assertThat(q.options().stream().filter(o -> o.deviceType().equals("robot")))
                .allMatch(o -> "STEEP_TERRAIN".equals(o.unavailableReason()));
        assertThat(q.options().stream().filter(o -> o.deviceType().equals("drone")).anyMatch(QuoteResponse.Option::available)).isTrue();
    }

    @Test
    void deadlineMissedKeepsReferencePriceAndEta() {
        QuoteResponse q = service.createQuote(USER, req(1.5, FERRY, ORACLE, 1));
        QuoteResponse.Option robot = find(q, "SoMa", "robot");
        assertThat(robot.unavailableReason()).isEqualTo("DEADLINE_MISSED");
        assertThat(robot.priceCents()).isNotNull();
        assertThat(robot.etaAt()).isNotNull();
    }

    @Test
    void weightIsRoundedToTwoDecimalsForPriceAndStorage() {
        service.createQuote(USER, req(1.234, FERRY, ORACLE, null));
        assertThat(saved.weightKg()).isEqualByComparingTo("1.23");
    }

    @Test
    void serviceAreaComesFromConfiguration() {
        QuoteService tiny = newService(new ServiceAreaProperties(37.77, 37.80, -122.40, -122.38),
                new QuoteProperties(10, 2, 10, 15, 1.5, 15, 1, 2),
                new PricingProperties(new BigDecimal("1.00"), 100));
        assertThatThrownBy(() -> tiny.createQuote(USER, req(1, FERRY, TWIN_PEAKS, null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("OUT_OF_SERVICE_AREA");
        assertThat(tiny.createQuote(USER, req(1, FERRY, ORACLE, null)).options()).hasSize(6);
    }

    @Test
    void routingCallsRunInParallel() {
        QuoteProperties props = new QuoteProperties(10, 2, 10, 15, 1.5, 15, 1, 2);
        PricingProperties pricing = new PricingProperties(new BigDecimal("1.00"), 100);
        QuoteService slow = new QuoteService(new InMemoryStationRepository(), (s, p, d) -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return OsmRoutingClient.fallback(s, p, d, 1.3);
        }, props, pricing, new ServiceAreaProperties(37.70, 37.84, -122.52, -122.35), q -> { }, Clock.fixed(now, ZoneOffset.UTC));
        long start = System.nanoTime();
        assertThat(slow.createQuote(USER, req(1.5, FERRY, ORACLE, null)).options()).hasSize(6);
        assertThat((System.nanoTime() - start) / 1_000_000).as("3 routes x 300 ms in parallel").isLessThan(700);
    }

    @Test
    void noActiveStationsIsA503NotAnEmptyQuote() {
        StationRepository none = new StationRepository() {
            public List<Station> findActiveStations() {
                return List.of();
            }

            public List<Device> findActiveDevices(Station station, DeviceType type) {
                return List.of();
            }
        };
        QuoteService empty = new QuoteService(none, (s, p, d) -> OsmRoutingClient.fallback(s, p, d, 1.3),
                new QuoteProperties(10, 2, 10, 15, 1.5, 15, 1, 2),
                new PricingProperties(new BigDecimal("1.00"), 100),
                new ServiceAreaProperties(37.70, 37.84, -122.52, -122.35), q -> { }, Clock.fixed(now, ZoneOffset.UTC));
        assertThatThrownBy(() -> empty.createQuote(USER, req(1, FERRY, ORACLE, null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("NO_STATIONS");
    }

    @Test
    void withoutLoginTheDefaultProviderRefuses() {
        assertThatThrownBy(() -> new UnauthenticatedCurrentUserProvider().currentUserId())
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void sameAddressAndOutOfAreaAreRejected() {
        assertThatThrownBy(() -> service.createQuote(USER, req(1, FERRY, FERRY, null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("SAME_ADDRESS");
        QuoteRequest.Place la = new QuoteRequest.Place("Los Angeles", 34.05, -118.24);
        assertThatThrownBy(() -> service.createQuote(USER, req(1, FERRY, la, null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("OUT_OF_SERVICE_AREA");
    }
}
