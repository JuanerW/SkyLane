package com.skylane.quote;

import com.skylane.routing.OsmRoutingClient;
import com.skylane.routing.RoutingClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs the whole API with the 'memory' profile, so it needs no database. */
@ActiveProfiles("memory")
@SpringBootTest
@AutoConfigureMockMvc
class QuoteApiMemoryTest {
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

    @Test
    void stationsAndQuoteWorkWithoutDatabase() throws Exception {
        mvc.perform(get("/api/v1/stations"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        String body = """
                {"item":{"description":"Cake","fragile":true},"weight_kg":1.5,
                 "pickup":{"text":"Ferry Building","lat":37.7955,"lng":-122.3937},
                 "dropoff":{"text":"Oracle Park","lat":37.7786,"lng":-122.3893}}""";
        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.options.length()").value(6));
    }

    @Test
    void tableViewReturnsPlainTextAndJsonStaysTheDefault() throws Exception {
        String body = """
                {"item":{"description":"Cake","fragile":true},"weight_kg":1.5,
                 "pickup":{"text":"Ferry Building","lat":37.7955,"lng":-122.3937},
                 "dropoff":{"text":"Oracle Park","lat":37.7786,"lng":-122.3893}}""";
        mvc.perform(post("/api/v1/quotes?format=table").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.containsString("Station  Device")));
        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.options.length()").value(6));
    }

    @Test
    void tinyWeightIsRejectedNotA500() throws Exception {
        String body = """
                {"item":{"description":"x","fragile":false},"weight_kg":0.001,
                 "pickup":{"text":"A","lat":37.7955,"lng":-122.3937},
                 "dropoff":{"text":"B","lat":37.7786,"lng":-122.3893}}""";
        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void frameworkErrorsUseTheUnifiedErrorBody() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/quotes"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.details").exists());
        mvc.perform(get("/api/v1/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
    @Test
    void weightPrecisionAndDroneLimitAreEnforced() throws Exception {
        String body = """
                {"item":{"description":"Cake","fragile":true},"weight_kg":2.004,
                 "pickup":{"text":"Ferry Building","lat":37.7955,"lng":-122.3937},
                 "dropoff":{"text":"Oracle Park","lat":37.7786,"lng":-122.3893}}""";
        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("2.004", "2.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.options[?(@.device_type=='drone' && @.available==true)]").value(org.hamcrest.Matchers.hasSize(3)));
        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("2.004", "2.01")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.options[?(@.device_type=='drone' && @.unavailable_reason=='OVERWEIGHT')]").value(org.hamcrest.Matchers.hasSize(3)));
    }
}
