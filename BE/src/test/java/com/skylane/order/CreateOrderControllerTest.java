package com.skylane.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skylane.common.ApiExceptionHandler;
import com.skylane.quote.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CreateOrderControllerTest {
    OrderService service;
    MockMvc mvc;
    final UUID user = UUID.randomUUID();
    final String body = """
            {"quote_id":"11111111-1111-1111-1111-111111111111",
             "option_id":"22222222-2222-2222-2222-222222222222",
             "recipient":{"name":"Ziyue","phone":"(415) 555-0100"}}
            """;

    @BeforeEach
    void setup() {
        service = mock(OrderService.class);
        CurrentUserProvider users = () -> user;
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(service, users))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    @Test
    void acceptsSnakeCaseRequestAndReturnsCreatedOrder() throws Exception {
        when(service.create(eq(user), eq("request-1"), any())).thenReturn(
                new OrderDtos.CreateOrderResponse("order-1", "confirmed",
                        new OrderDtos.DeviceRef("R-101", "robot"), 150,
                        Instant.parse("2026-11-02T12:00:00Z"), Instant.parse("2026-11-02T12:05:00Z")));
        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.order_id").value("order-1"))
                .andExpect(jsonPath("$.price_cents").value(150))
                .andExpect(jsonPath("$.device.device_type").value("robot"));
        verify(service).create(eq(user), eq("request-1"), argThat(r ->
                r.recipient().name().equals("Ziyue") && r.quoteId().startsWith("11111111")));
    }

    @Test
    void listUsesDefaultOrExplicitLimitAndReturnsArray() throws Exception {
        when(service.list(eq(user), anyInt())).thenReturn(java.util.List.of());
        mvc.perform(get("/api/v1/orders"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).list(user, 100);
        mvc.perform(get("/api/v1/orders").param("limit", "3"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).list(user, 3);
    }

    @Test
    void invalidListLimitIsAClientErrorWithoutCallingService() throws Exception {
        for (String value : new String[]{"abc", "1.5", "2147483648"}) {
            mvc.perform(get("/api/v1/orders").param("limit", value))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMissingRecipientAndBlankNameBeforeCallingService() throws Exception {
        for (String invalid : new String[]{"{}", body.replace("\"Ziyue\"", "\" \""),
                body.replace("\"(415) 555-0100\"", "\"\"")}) {
            mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(service);
    }
}
