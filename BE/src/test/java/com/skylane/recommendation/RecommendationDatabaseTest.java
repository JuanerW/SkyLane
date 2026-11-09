package com.skylane.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skylane.auth.JwtService;
import com.skylane.routing.OsmRoutingClient;
import com.skylane.routing.RoutingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in: use a disposable PostGIS database, not the developer's working database. */
@EnabledIfEnvironmentVariable(named = "SKYLANE_TEST_DB", matches = ".+")
@ActiveProfiles("dev")
@SpringBootTest
@AutoConfigureMockMvc
class RecommendationDatabaseTest {
    @TestConfiguration
    static class OfflineRoutes {
        @Bean @Primary RoutingClient offlineRouting() {
            return (s, p, d) -> OsmRoutingClient.fallback(s, p, d, 1.3);
        }
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    @Autowired RecommendationService service;
    @MockBean WindClient wind;
    private UUID alice;
    private UUID bob;

    @BeforeEach void setup() {
        alice = user("alice@skylane.dev");
        bob = user("bob@skylane.dev");
        when(wind.currentWind(anyDouble(), anyDouble())).thenReturn(null);
    }
    private UUID user(String email) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", UUID.class, email);
    }
    private String token(UUID id) { return "Bearer " + jwt.issue(id, 0); }
    private UUID quote() throws Exception {
        String request = """
                {"item":{"description":"Cake","fragile":true},"weight_kg":1.5,
                 "pickup":{"text":"Ferry Building","lat":37.7955,"lng":-122.3937},
                 "dropoff":{"text":"Oracle Park","lat":37.7786,"lng":-122.3893},"notes":"cheap please"}
                """;
        String body = mvc.perform(post("/api/v1/quotes").header("Authorization", token(alice))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(body).get("quote_id").asText());
    }
    private String endpoint(UUID quote) { return "/api/v1/quotes/" + quote + "/recommendation"; }
    private int count(UUID quote) {
        return jdbc.queryForObject("SELECT count(*) FROM recommendation WHERE quote_id = ?", Integer.class, quote);
    }
    @Test void migratesAndSeedsTheRealDatabase() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM station", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device", Integer.class)).isEqualTo(15);
    }
    @Test void persistsOneCachedResultAndSurvivesANewServiceInstance() throws Exception {
        UUID q = quote();
        String first = mvc.perform(post(endpoint(q)).header("Authorization", token(alice)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.factors.preference").value("price"))
                .andExpect(jsonPath("$.factors.weather_status").value("unavailable"))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post(endpoint(q)).header("Authorization", token(alice)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(second)).isEqualTo(mapper.readTree(first));
        assertThat(count(q)).isEqualTo(1);
        JsonNode cached = mapper.readTree(first);
        UUID optionId = UUID.fromString(cached.get("option_id").asText());
        assertThat(jdbc.queryForObject("SELECT available FROM quote_option WHERE quote_id = ? AND id = ?", Boolean.class, q, optionId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT model IS NULL FROM recommendation WHERE quote_id = ?", Boolean.class, q)).isTrue();
        RecommendationService cold = new RecommendationService(jdbc, mapper, Clock.systemUTC(), wind, 10,
                new ReasonWriter(new com.skylane.agent.AgentProperties("", "m", "http://localhost", 1, 1, 1, 1, 1, 10), mapper));
        assertThat(cold.recommend(alice, q).optionId()).isEqualTo(optionId);
        assertThat(count(q)).isEqualTo(1);
    }
    @Test void rejectsAnotherUserEvenWhenTheRecommendationIsCached() throws Exception {
        UUID q = quote();
        service.recommend(alice, q);
        mvc.perform(post(endpoint(q)).header("Authorization", token(bob)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("QUOTE_NOT_FOUND"));
        assertThat(count(q)).isEqualTo(1);
    }
    @Test void requiresLoginAndHidesUnknownQuotes() throws Exception {
        UUID q = quote();
        mvc.perform(post(endpoint(q))).andExpect(status().isUnauthorized());
        mvc.perform(post(endpoint(UUID.randomUUID())).header("Authorization", token(alice)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("QUOTE_NOT_FOUND"));
        assertThat(count(q)).isZero();
    }
    @Test void rejectsAnExpiredQuoteIncludingAnExistingCache() throws Exception {
        UUID q = quote(); service.recommend(alice, q);
        jdbc.update("UPDATE quote SET expires_at = now() - interval '1 minute' WHERE id = ?", q);
        mvc.perform(post(endpoint(q)).header("Authorization", token(alice)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("QUOTE_EXPIRED"));
        assertThat(count(q)).isEqualTo(1);
    }
    @Test void neverRecommendsUnavailableOptions() throws Exception {
        UUID q = quote();
        jdbc.update("UPDATE quote_option SET available = false, unavailable_reason = 'NO_DEVICE_AVAILABLE' WHERE quote_id = ?", q);
        mvc.perform(post(endpoint(q)).header("Authorization", token(alice)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("NO_AVAILABLE_OPTIONS"));
        assertThat(count(q)).isZero();
    }
    @Test void concurrentRequestsPersistExactlyOneResult() throws Exception {
        UUID q = quote();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<RecommendationService.Response>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) results.add(executor.submit(() -> {
                start.await(); return service.recommend(alice, q);
            }));
            start.countDown();
            UUID selected = results.getFirst().get(15, TimeUnit.SECONDS).optionId();
            for (var result : results) assertThat(result.get(15, TimeUnit.SECONDS).optionId()).isEqualTo(selected);
        }
        assertThat(count(q)).isEqualTo(1);
    }
}
