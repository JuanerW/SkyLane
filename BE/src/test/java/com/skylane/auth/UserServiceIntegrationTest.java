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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    @Autowired PlatformTransactionManager tm;
    @MockBean DeviceSimulator simulator;
    @MockBean GoogleIdTokenVerifier google;

    final String tag = UUID.randomUUID().toString().substring(0, 8);
    final String email = "auth-" + tag + "@example.test";

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM app_user WHERE email LIKE ?", "%" + tag + "@%");
        jdbc.update("DELETE FROM operator WHERE name = ?", "op-" + tag);
    }

    void googleSays(String sub, String mail, String name) {
        googleSays(sub, mail, name, "");
    }

    void googleSays(String sub, String mail, String name, String hd) {
        when(google.verify(any())).thenReturn(new GoogleIdTokenVerifier.GoogleIdentity(sub, mail, name, hd));
    }

    static Object outcome(Callable<?> call) {
        try {
            return call.call();
        } catch (Exception e) {
            return e;
        }
    }

    /** Releases the calls together on separate threads and returns each outcome (a value or the exception). */
    <T> List<Object> concurrently(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return outcome(call);
                }));
            }
            go.countDown();
            List<Object> out = new ArrayList<>();
            for (Future<Object> f : futures) out.add(f.get(30, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
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
    void linkingGoogleToAnExistingAccountKeepsItButRevokesItsPasswordAndSessions() throws Exception {
        // someone who does not own the address pre-registered it (register() does not verify email) and kept a session
        var preRegistered = users.register(new RegisterRequest(email, "attackers-password", "Ann"));
        meWith(preRegistered.accessToken(), 200);

        googleSays("sub-" + tag, email.toUpperCase(), "Ann From Google", "example.test");   // the real owner, via Workspace
        var linked = users.googleLogin("tok");
        assertThat(linked.user().id()).isEqualTo(preRegistered.user().id());   // same account, so the owner keeps their data
        assertThat(linked.user().googleLinked()).isTrue();
        assertThat(linked.user().hasPassword()).isFalse();
        assertThat(linked.user().displayName()).isEqualTo("Ann");              // an existing name is not overwritten

        meWith(preRegistered.accessToken(), 401);                              // the earlier session is dead
        meWith(linked.accessToken(), 200);                                     // the owner's new one works
        assertThatThrownBy(() -> users.login(new LoginRequest(email, "attackers-password"))).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_CREDENTIALS"));
    }

    @Test
    void googleSignInRefusesAnEmailLinkedToADifferentGoogleAccount() {
        googleSays("sub-a-" + tag, email, "Ann", "example.test");   // a trusted address, so linking is allowed
        users.googleLogin("tok");
        googleSays("sub-b-" + tag, email, "Impostor", "example.test");
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

    @Test
    void googleDoesNotTakeOverAnExistingAccountThroughAnAddressGoogleDoesNotControl() {
        var registered = users.register(new RegisterRequest(email, "password123", "Ann"));
        googleSays("sub-" + tag, email, "Someone Else");   // email_verified, but no hd and not gmail.com
        assertThatThrownBy(() -> users.googleLogin("tok")).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status().value()).isEqualTo(409);
            assertThat(e.code()).isEqualTo("GOOGLE_EMAIL_NOT_TRUSTED");
        });
        assertThat(jdbc.queryForObject("SELECT google_sub FROM app_user WHERE id = ?", String.class,
                UUID.fromString(registered.user().id()))).isNull();
    }

    @Test
    void googleLinksAnExistingAccountThroughAGmailAddress() {
        String gmail = "auth-" + tag + "@gmail.com";
        var registered = users.register(new RegisterRequest(gmail, "password123", "Ann"));
        googleSays("sub-" + tag, gmail, "Ann");
        assertThat(users.googleLogin("tok").user().id()).isEqualTo(registered.user().id());
    }

    @Test
    void anUntrustedAddressStillGetsAFreshAccountWhenNoneExists() {
        googleSays("sub-" + tag, email, "New Person");
        assertThat(users.googleLogin("tok").user().googleLinked()).isTrue();
    }

    @Test
    void simultaneousFirstGoogleSignInsOfOneIdentityAllSucceedAndCreateOneAccount() throws Exception {
        googleSays("sub-" + tag, email, "Ann");
        List<Callable<AuthDtos.AuthResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) calls.add(() -> users.googleLogin("tok"));
        List<Object> results = concurrently(calls);
        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(AuthDtos.AuthResponse.class));
        assertThat(results.stream().map(r -> ((AuthDtos.AuthResponse) r).user().id()).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void simultaneousFirstGoogleSignInsLinkAnExistingAccountWithoutAFalseConflict() throws Exception {
        var registered = users.register(new RegisterRequest(email, "password123", "Ann"));
        googleSays("sub-" + tag, email, "Ann", "example.test");
        List<Callable<AuthDtos.AuthResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) calls.add(() -> users.googleLogin("tok"));
        List<Object> results = concurrently(calls);
        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(AuthDtos.AuthResponse.class));
        assertThat(results.stream().map(r -> ((AuthDtos.AuthResponse) r).user().id()).distinct())
                .containsExactly(registered.user().id());
        for (Object r : results) meWith(((AuthDtos.AuthResponse) r).accessToken(), 200);   // none carries a stale token_version
    }

    /**
     * Deterministic: this test holds the user row lock itself, so a changePassword that does not lock the row would
     * finish at once and fail the "still blocked" assertions.
     */
    @Test
    void changingAPasswordWaitsForTheUserRowLockSoTwoFirstPasswordsCannotOverwriteEachOther() throws Exception {
        googleSays("sub-" + tag, email, "Ann");
        UUID id = UUID.fromString(users.googleLogin("tok").user().id());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> holder = pool.submit(() -> new TransactionTemplate(tm).executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM app_user WHERE id = ? FOR UPDATE", UUID.class, id);
                holding.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Object> first = pool.submit(() -> outcome(
                    () -> users.changePassword(id, new ChangePasswordRequest(null, "first-password-1"))));
            Future<Object> second = pool.submit(() -> outcome(
                    () -> users.changePassword(id, new ChangePasswordRequest(null, "second-password-2"))));
            Thread.sleep(1000);
            assertThat(first.isDone()).as("first change is waiting on the row lock").isFalse();
            assertThat(second.isDone()).as("second change is waiting on the row lock").isFalse();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            List<Object> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().filter(r -> r instanceof AuthDtos.AuthResponse).count()).isEqualTo(1);
            Object loser = results.stream().filter(r -> r instanceof ApiException).findFirst().orElseThrow();
            assertThat(((ApiException) loser).code()).isEqualTo("INVALID_CURRENT_PASSWORD");   // it saw the winner's password
            String winner = results.get(0) instanceof AuthDtos.AuthResponse ? "first-password-1" : "second-password-2";
            assertThat(users.login(new LoginRequest(email, winner)).user().id()).isEqualTo(id.toString());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oneGoogleIdentityShowingUpWithTwoEmailsNeverCausesAServerError() throws Exception {
        for (int round = 0; round < 6; round++) {
            String sub = "sub-r" + round + "-" + tag;
            String mailA = "auth-a" + round + "-" + tag + "@example.test";
            String mailB = "auth-b" + round + "-" + tag + "@example.test";
            users.register(new RegisterRequest(mailA, "password123", "A"));
            users.register(new RegisterRequest(mailB, "password123", "B"));
            when(google.verify("tok-a")).thenReturn(new GoogleIdTokenVerifier.GoogleIdentity(sub, mailA, "A", "example.test"));
            when(google.verify("tok-b")).thenReturn(new GoogleIdTokenVerifier.GoogleIdentity(sub, mailB, "B", "example.test"));
            for (Object r : concurrently(List.of(() -> users.googleLogin("tok-a"), () -> users.googleLogin("tok-b")))) {
                if (r instanceof Exception e) {
                    // depending on timing the second request may log in as the first account, or be refused; never a 500
                    assertThat(e).isInstanceOfSatisfying(ApiException.class,
                            api -> assertThat(api.code()).isEqualTo("GOOGLE_ACCOUNT_MISMATCH"));
                }
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE google_sub = ?", Integer.class, sub)).isEqualTo(1);
        }
    }
}
