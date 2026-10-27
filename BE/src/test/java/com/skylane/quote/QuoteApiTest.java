package com.skylane.quote;

import com.skylane.routing.OsmRoutingClient;
import com.skylane.routing.RoutingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs with profile 'dev' (fake logged-in test user). Needs the PostGIS database with Flyway V1 and database/dev-seed-quote.sql applied. Run with
 * SKYLANE_TEST_DB=1 DB_URL=jdbc:postgresql://localhost:5433/skylane mvn test
 */
@EnabledIfEnvironmentVariable(named = "SKYLANE_TEST_DB", matches = ".+")
@ActiveProfiles("dev")
@SpringBootTest
@AutoConfigureMockMvc
class QuoteApiTest {
    @TestConfiguration
    static class Offline {
        @Bean
        @Primary
        RoutingClient offlineRouting() {
            return (s, p, d) -> OsmRoutingClient.fallback(s, p, d, 1.3);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    static final String DEMO = """
            {"item":{"description":"Cake","fragile":true},"weight_kg":1.5,
             "pickup":{"text":"Ferry Building","lat":37.7955,"lng":-122.3937},
             "dropoff":{"text":"Oracle Park","lat":37.7786,"lng":-122.3893}}""";

    @Test
    void listsThreeStationsAsAnArray() throws Exception {
        mvc.perform(get("/api/v1/stations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").exists())
                .andExpect(jsonPath("$[0].lat").exists());
    }

    @Test
    void createsAndPersistsQuoteWithSixOptions() throws Exception {
        int quotesBefore = jdbc.queryForObject("SELECT count(*) FROM quote", Integer.class);
        String body = mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(DEMO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.options.length()").value(6))
                .andExpect(jsonPath("$.options[0].route_points[0].length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String quoteId = com.jayway.jsonpath.JsonPath.read(body, "$.quote_id");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM quote", Integer.class)).isEqualTo(quotesBefore + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quote_option WHERE quote_id = ?::uuid", Integer.class, quoteId)).isEqualTo(6);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM quote_option WHERE quote_id = ?::uuid AND available", Integer.class, quoteId))
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void busyDeviceBlockPushesDepartureLater() throws Exception {
        // Block every SoMa drone for 30 minutes through a fake order-less 'maintenance' block.
        jdbc.update("""
                INSERT INTO device_block (device_id, kind, starts_at, ends_at)
                SELECT d.id, 'maintenance', now() - interval '1 minute', now() + interval '30 minutes'
                FROM device d JOIN station s ON s.id = d.station_id WHERE s.code = 'soma' AND d.device_type = 'drone'""");
        try {
            String body = mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(DEMO))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            java.util.List<String> departs = com.jayway.jsonpath.JsonPath.read(body,
                    "$.options[?(@.station.name=='SoMa Station' && @.device_type=='drone')].departs_at");
            assertThat(java.time.Instant.parse(departs.get(0))).isAfter(java.time.Instant.now().plusSeconds(25 * 60));
        } finally {
            jdbc.update("DELETE FROM device_block WHERE kind = 'maintenance'");
        }
    }

    @Test
    void rejectsBadRequests() throws Exception {
        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details").exists());
        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON)
                        .content(DEMO.replace("\"weight_kg\":1.5", "\"weight_kg\":0.001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON)
                        .content(DEMO.replace("\"weight_kg\":1.5", "\"weight_kg\":11")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON)
                        .content(DEMO.replace("37.7786", "37.7955").replace("-122.3893", "-122.3937")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SAME_ADDRESS"));
    }
}
