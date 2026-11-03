package com.skylane.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skylane.common.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReviewOrderControllerTest {
    OrderService service;
    MockMvc mvc;
    ObjectMapper mapper;
    final UUID user = UUID.randomUUID(), order = UUID.randomUUID();
    String path() { return "/api/v1/orders/" + order + "/review"; }

    @BeforeEach
    void setup() {
        service = mock(OrderService.class);
        mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(service, () -> user))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    @Test
    void createsReviewUsingAuthenticatedUserAndReturnsSavedValues() throws Exception {
        when(service.review(user, order, 5, "Great")).thenReturn(new OrderDtos.Review(5, "Great", Instant.now()));
        mvc.perform(post(path()).contentType("application/json").content("{\"rating\":5,\"comment\":\"Great\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.comment").value("Great")).andExpect(jsonPath("$.created_at").exists());
        verify(service).review(user, order, 5, "Great");
    }

    @Test
    void rejectsMissingOutOfRangeAndNonIntegerRatingsWithoutWriting() throws Exception {
        for (String value : new String[]{"null", "0", "6", "-1", "4.9", "5.0", "true", "\"5\"", "2147483648", "[]", "{}"}) {
            mvc.perform(post(path()).contentType("application/json").content(mapper.writeValueAsString(java.util.Map.of("rating", mapper.readTree(value)))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        mvc.perform(post(path()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsOverlongCommentsAndMalformedBodies() throws Exception {
        for (String body : new String[]{"{\"rating\":5,\"comment\":\"" + "x".repeat(1001) + "\"}", "{", "null"}) {
            mvc.perform(post(path()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(service);
    }
}
