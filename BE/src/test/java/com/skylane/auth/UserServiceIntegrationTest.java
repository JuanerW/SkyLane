package com.skylane.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skylane.auth.AuthDtos.ChangePasswordRequest;
import com.skylane.auth.AuthDtos.LoginRequest;
import com.skylane.auth.AuthDtos.RegisterRequest;
import com.skylane.common.ApiException;
import com.skylane.tracking.DeviceSimulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real PostgreSQL, real JWT filter. Opt in with SKYLANE_AUTH_TEST_DB=1 against a disposable database
 * (see src/test/java/com/skylane/order/README.md for the connection variables). Cleans up what it creates.
 */
@EnabledIfEnvironmentVariable(named = "SKYLANE_AUTH_TEST_DB", matches = "1")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
class UserServiceIntegrationTest {
    @Autowired UserService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockBean DeviceSimulator simulator;
    @MockBean GoogleIdTokenVerifier google;

    final String tag = UUID.randomUUID().toString().substring(0, 8);
    final String email = "auth-" + tag + "@example.test";

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM app_user WHERE email LIKE ?", "%" + tag + "@example.test");
        jdbc.update("DELETE FROM operator WHERE name = ?", "op-" + tag);
    }

    void googleSays(String sub, String mail, String name) {
        when(google.verify(any())).thenReturn(new GoogleIdTokenVerifier.GoogleIdentity(sub, mail, name));
    }

    String meWith(String token, int expectedStatus) throws Exception {
        return mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void googleSignUpCreatesAPasswordlessCustomerAndSignsInAgain() {
        googleSays("sub-" + tag, email, "Ann Lee");
        var first = users.googleLogin("tok");
        assertThat(first.user().email()).isEqualTo(email);
        assertThat(first.user().displayName()).isEqualTo("Ann Lee");
        assertThat(first.user().googleLinked()).isTrue();
        assertThat(first.user().hasPassword()).isFalse();
        assertThat(first.user().role()).isEqualTo("customer");
        assertThat(first.user().operatorId()).isNull();
        assertThat(users.googleLogin("tok").user().id()).isEqualTo(first.user().id());
    }

    @Test
    void googleSignInLinksAnExistingPasswordAccountByEmail() {
        var registered = users.register(new RegisterRequest(email, "password123", "Ann"));
        googleSays("sub-" + tag, email.toUpperCase(), "Ann From Google");
        var linked = users.googleLogin("tok");
        assertThat(linked.user().id()).isEqualTo(registered.user().id());
        assertThat(linked.user().googleLinked()).isTrue();
        assertThat(linked.user().hasPassword()).isTrue();
        assertThat(linked.user().displayName()).isEqualTo("Ann");   // an existing name is not overwritten
        assertThat(users.login(new LoginRequest(email, "password123")).user().id()).isEqualTo(registered.user().id());
    }

    @Test
    void googleSignInRefusesAnEmailLinkedToADifferentGoogleAccount() {
        googleSays("sub-a-" + tag, email, "Ann");
        users.googleLogin("tok");
        googleSays("sub-b-" + tag, email, "Impostor");
        assertThatThrownBy(() -> users.googleLogin("tok")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("GOOGLE_ACCOUNT_MISMATCH"));
    }

    @Test
    void disabledAccountGets403OnLoginButOnlyAfterTheCorrectPassword() throws Exception {
        var registered = users.register(new RegisterRequest(email, "password123", "Ann"));
        jdbc.update("UPDATE app_user SET disabled_at = now() WHERE id = ?", UUID.fromString(registered.user().id()));

        assertThatThrownBy(() -> users.login(new LoginRequest(email, "password123"))).isInstanceOfSatisfying(
                ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("ACCOUNT_DISABLED");
                });
        // a wrong password must not reveal that the account exists or is disabled
        assertThatThrownBy(() -> users.login(new LoginRequest(email, "wrong-password"))).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_CREDENTIALS"));
        // an already-issued token stops working
        meWith(registered.accessToken(), 401);
    }

    @Test
    void disabledAccountGets403OnGoogleLogin() {
        googleSays("sub-" + tag, email, "Ann");
        var created = users.googleLogin("tok");
        jdbc.update("UPDATE app_user SET disabled_at = now() WHERE id = ?", UUID.fromString(created.user().id()));
        assertThatThrownBy(() -> users.googleLogin("tok")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("ACCOUNT_DISABLED"));
    }

    @Test
    void changingThePasswordSignsOutOldTokensButKeepsTheCurrentSession() throws Exception {
        var registered = users.register(new RegisterRequest(email, "password123", "Ann"));
        UUID id = UUID.fromString(registered.user().id());

        assertThatThrownBy(() -> users.changePassword(id, new ChangePasswordRequest("wrong-password", "newpassword1")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_CURRENT_PASSWORD"));
        assertThatThrownBy(() -> users.changePassword(id, new ChangePasswordRequest(null, "newpassword1")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_CURRENT_PASSWORD"));
        meWith(registered.accessToken(), 200);   // failed attempts changed nothing

        var changed = users.changePassword(id, new ChangePasswordRequest("password123", "newpassword1"));
        meWith(registered.accessToken(), 401);   // the old token (token_version 0) is dead
        meWith(changed.accessToken(), 200);      // the fresh one works
        assertThat(users.login(new LoginRequest(email, "newpassword1")).user().id()).isEqualTo(registered.user().id());
        assertThatThrownBy(() -> users.login(new LoginRequest(email, "password123"))).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_CREDENTIALS"));
    }

    @Test
    void googleOnlyAccountCanSetAFirstPasswordWithoutACurrentOne() {
        googleSays("sub-" + tag, email, "Ann");
        var created = users.googleLogin("tok");
        UUID id = UUID.fromString(created.user().id());

        var set = users.changePassword(id, new ChangePasswordRequest(null, "firstpassword1"));
        assertThat(set.user().hasPassword()).isTrue();
        assertThat(users.login(new LoginRequest(email, "firstpassword1")).user().id()).isEqualTo(created.user().id());
        // from now on a current password is required like for anyone else
        assertThatThrownBy(() -> users.changePassword(id, new ChangePasswordRequest(null, "secondpassword1")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_CURRENT_PASSWORD"));
    }

    @Test
    void patchMeTrimsTheNameAndMeReportsRoleAndOperator() throws Exception {
        var registered = users.register(new RegisterRequest(email, "password123", "Ann"));
        mvc.perform(patch("/api/v1/me").header("Authorization", "Bearer " + registered.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"display_name\":\"  Ann Lee  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.display_name").value("Ann Lee"));
        mvc.perform(patch("/api/v1/me").contentType(MediaType.APPLICATION_JSON).content("{\"display_name\":\"X\"}"))
                .andExpect(status().isUnauthorized());

        UUID operator = jdbc.queryForObject("INSERT INTO operator (name) VALUES (?) RETURNING id", UUID.class, "op-" + tag);
        jdbc.update("UPDATE app_user SET role = 'operator', operator_id = ? WHERE id = ?", operator,
                UUID.fromString(registered.user().id()));
        JsonNode me = mapper.readTree(meWith(registered.accessToken(), 200));
        assertThat(me.get("role").asText()).isEqualTo("operator");
        assertThat(me.get("operator_id").asText()).isEqualTo(operator.toString());
        assertThat(me.get("display_name").asText()).isEqualTo("Ann Lee");
    }
}
