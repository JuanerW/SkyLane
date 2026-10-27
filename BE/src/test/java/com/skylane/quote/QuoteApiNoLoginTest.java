package com.skylane.quote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Default profile (no 'dev', no 'memory'): nobody is logged in, so quotes must be refused. Needs the database. */
@EnabledIfEnvironmentVariable(named = "SKYLANE_TEST_DB", matches = ".+")
@SpringBootTest
@AutoConfigureMockMvc
class QuoteApiNoLoginTest {
    @Autowired
    MockMvc mvc;

    @Test
    void quotesAreRefusedWithoutLogin() throws Exception {
        String body = """
                {"item":{"description":"x","fragile":false},"weight_kg":1,
                 "pickup":{"text":"A","lat":37.7955,"lng":-122.3937},
                 "dropoff":{"text":"B","lat":37.7786,"lng":-122.3893}}""";
        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
