package com.skylane.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skylane.auth.AuthDtos.AuthResponse;
import com.skylane.auth.AuthDtos.UserView;
import com.skylane.common.ApiException;
import com.skylane.common.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP shape only (snake_case, validation, error bodies); the SQL behind it is in UserServiceIntegrationTest. */
class AuthControllerTest {
    final UUID user = UUID.randomUUID();
    final UserView view = new UserView(user.toString(), "ann@example.com", "Ann", true, false,
            Instant.parse("2026-11-02T12:00:00Z"), "customer", null);
    UserService service;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(UserService.class);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(service, () -> user))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    @Test
    void googleLoginReturnsTokenAndUserWithRole() throws Exception {
        when(service.googleLogin("tok")).thenReturn(new AuthResponse("jwt-1", view));
        mvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON).content("{\"id_token\":\"tok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("jwt-1"))
                .andExpect(jsonPath("$.user.role").value("customer"))
                .andExpect(jsonPath("$.user.operator_id").value((Object) null));
    }

    @Test
    void googleLoginRequiresAnIdToken() throws Exception {
        mvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(service, never()).googleLogin(any());
    }

    @Test
    void disabledAccountSurfacesAs403() throws Exception {
        when(service.googleLogin("tok")).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "disabled"));
        mvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON).content("{\"id_token\":\"tok\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void meReturnsRoleAndOperatorId() throws Exception {
        when(service.me(user)).thenReturn(view);
        mvc.perform(get("/api/v1/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("customer"))
                .andExpect(jsonPath("$.display_name").value("Ann"))
                .andExpect(jsonPath("$.has_password").value(true));
    }

    @Test
    void patchMeUpdatesTheDisplayNameOfTheCurrentUser() throws Exception {
        when(service.updateProfile(eq(user), any())).thenReturn(view);
        mvc.perform(patch("/api/v1/me").contentType(MediaType.APPLICATION_JSON).content("{\"display_name\":\"Ann\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.display_name").value("Ann"));
    }

    @Test
    void patchMeRejectsBlankAndOverlongNames() throws Exception {
        for (String name : new String[]{"", "   ", "x".repeat(81)}) {
            mvc.perform(patch("/api/v1/me").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"display_name\":\"" + name + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        verify(service, never()).updateProfile(any(), any());
    }

    @Test
    void putPasswordReturnsAFreshToken() throws Exception {
        when(service.changePassword(eq(user), any())).thenReturn(new AuthResponse("jwt-2", view));
        mvc.perform(put("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"oldpassword\",\"new_password\":\"newpassword1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access_token").value("jwt-2"));
    }

    @Test
    void putPasswordRejectsAShortNewPassword() throws Exception {
        mvc.perform(put("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"oldpassword\",\"new_password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(service, never()).changePassword(any(), any());
    }
}
