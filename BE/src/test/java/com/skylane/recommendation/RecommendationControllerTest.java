package com.skylane.recommendation;

import com.skylane.common.ApiException;
import com.skylane.common.ApiExceptionHandler;
import com.skylane.quote.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RecommendationControllerTest {
    private final RecommendationService service = mock(RecommendationService.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID quoteId = UUID.randomUUID();
    private MockMvc mvc;
    @BeforeEach void setup() {
        CurrentUserProvider user = () -> userId;
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        mvc = MockMvcBuilders.standaloneSetup(new RecommendationController(service, user))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper)).build();
    }
    @Test void returnsContractAndPassesCurrentUserToService() throws Exception {
        UUID option = UUID.randomUUID();
        when(service.recommend(userId, quoteId)).thenReturn(new RecommendationService.Response(option, "Balanced choice", Map.of("algorithm", "rules-v1")));
        mvc.perform(post("/api/v1/quotes/" + quoteId + "/recommendation"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.option_id").value(option.toString()))
                .andExpect(jsonPath("$.reason").value("Balanced choice")).andExpect(jsonPath("$.factors.algorithm").value("rules-v1"));
        verify(service).recommend(userId, quoteId);
    }
    @Test void malformedIdNeverReachesService() throws Exception {
        mvc.perform(post("/api/v1/quotes/not-a-uuid/recommendation"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("QUOTE_NOT_FOUND"));
        verifyNoInteractions(service);
    }
    @Test void preservesOwnershipAndExpiryErrors() throws Exception {
        when(service.recommend(userId, quoteId)).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "QUOTE_NOT_FOUND", "Quote not found"));
        mvc.perform(post("/api/v1/quotes/" + quoteId + "/recommendation")).andExpect(status().isNotFound());
        doThrow(new ApiException(HttpStatus.CONFLICT, "QUOTE_EXPIRED", "Refresh options")).when(service).recommend(userId, quoteId);
        mvc.perform(post("/api/v1/quotes/" + quoteId + "/recommendation"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("QUOTE_EXPIRED"));
    }
}
