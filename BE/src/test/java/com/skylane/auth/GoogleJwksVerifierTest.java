package com.skylane.auth;

import com.skylane.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No network: the signing key is generated here and handed to the verifier as its key source. */
class GoogleJwksVerifierTest {
    static final String CLIENT_ID = "skylane-test.apps.googleusercontent.com";
    static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    KeyPair key;
    KeyPair otherKey;
    AtomicInteger fetches;
    GoogleJwksVerifier verifier;

    @BeforeEach
    void setup() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        key = gen.generateKeyPair();
        otherKey = gen.generateKeyPair();
        fetches = new AtomicInteger();
        verifier = verifierFor(CLIENT_ID);
    }

    GoogleJwksVerifier verifierFor(String clientId) {
        Map<String, PublicKey> keys = Map.of("kid-1", key.getPublic());
        return new GoogleJwksVerifier(clientId, Clock.fixed(NOW, ZoneOffset.UTC), () -> {
            fetches.incrementAndGet();
            return keys;
        });
    }

    String token(KeyPair signer, String kid, String alg, String claims) throws Exception {
        String head = B64.encodeToString(("{\"alg\":\"" + alg + "\",\"kid\":\"" + kid + "\"}").getBytes(StandardCharsets.UTF_8));
        String body = B64.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(signer.getPrivate());
        sig.update((head + "." + body).getBytes(StandardCharsets.US_ASCII));
        return head + "." + body + "." + B64.encodeToString(sig.sign());
    }

    String claims(String iss, String aud, long exp, String emailVerified) {
        return "{\"iss\":\"" + iss + "\",\"aud\":\"" + aud + "\",\"exp\":" + exp + ",\"sub\":\"g-123\","
                + "\"email\":\"Ann@Example.com\",\"email_verified\":" + emailVerified + ",\"name\":\"Ann Lee\"}";
    }

    String good() throws Exception {
        return token(key, "kid-1", "RS256", claims("https://accounts.google.com", CLIENT_ID, NOW.getEpochSecond() + 3600, "true"));
    }

    void assertRejected(String token, String code) {
        assertRejected(verifier, token, code);
    }

    void assertRejected(GoogleJwksVerifier v, String token, String code) {
        assertThatThrownBy(() -> v.verify(token)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void acceptsAValidToken() throws Exception {
        var id = verifier.verify(good());
        assertThat(id.sub()).isEqualTo("g-123");
        assertThat(id.email()).isEqualTo("Ann@Example.com");
        assertThat(id.name()).isEqualTo("Ann Lee");
    }

    @Test
    void acceptsTheBareIssuerAndStringEmailVerified() throws Exception {
        assertThat(verifier.verify(token(key, "kid-1", "RS256",
                claims("accounts.google.com", CLIENT_ID, NOW.getEpochSecond() + 60, "\"true\"")))).isNotNull();
    }

    @Test
    void rejectsATokenSignedByAnotherKey() throws Exception {
        assertRejected(token(otherKey, "kid-1", "RS256",
                claims("https://accounts.google.com", CLIENT_ID, NOW.getEpochSecond() + 3600, "true")), "INVALID_GOOGLE_TOKEN");
    }

    @Test
    void rejectsAWrongAudience() throws Exception {
        assertRejected(token(key, "kid-1", "RS256",
                claims("https://accounts.google.com", "someone-else", NOW.getEpochSecond() + 3600, "true")), "INVALID_GOOGLE_TOKEN");
    }

    @Test
    void rejectsAWrongIssuer() throws Exception {
        assertRejected(token(key, "kid-1", "RS256",
                claims("https://evil.example", CLIENT_ID, NOW.getEpochSecond() + 3600, "true")), "INVALID_GOOGLE_TOKEN");
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        assertRejected(token(key, "kid-1", "RS256",
                claims("https://accounts.google.com", CLIENT_ID, NOW.getEpochSecond() - 120, "true")), "INVALID_GOOGLE_TOKEN");
    }

    @Test
    void toleratesSmallClockSkew() throws Exception {
        assertThat(verifier.verify(token(key, "kid-1", "RS256",
                claims("https://accounts.google.com", CLIENT_ID, NOW.getEpochSecond() - 30, "true")))).isNotNull();
    }

    @Test
    void rejectsAnUnverifiedEmail() throws Exception {
        assertRejected(token(key, "kid-1", "RS256",
                claims("https://accounts.google.com", CLIENT_ID, NOW.getEpochSecond() + 3600, "false")), "INVALID_GOOGLE_TOKEN");
    }

    @Test
    void rejectsAnUnsignedOrNonRs256Token() throws Exception {
        String head = B64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String body = B64.encodeToString(claims("https://accounts.google.com", CLIENT_ID, NOW.getEpochSecond() + 3600, "true")
                .getBytes(StandardCharsets.UTF_8));
        assertRejected(head + "." + body + ".", "INVALID_GOOGLE_TOKEN");
        assertRejected(token(key, "kid-1", "HS256",
                claims("https://accounts.google.com", CLIENT_ID, NOW.getEpochSecond() + 3600, "true")), "INVALID_GOOGLE_TOKEN");
    }

    @Test
    void rejectsGarbageAndUnknownKeyIds() throws Exception {
        assertRejected("not-a-token", "INVALID_GOOGLE_TOKEN");
        assertRejected(token(key, "unknown-kid", "RS256",
                claims("https://accounts.google.com", CLIENT_ID, NOW.getEpochSecond() + 3600, "true")), "INVALID_GOOGLE_TOKEN");
    }

    @Test
    void cachesKeysAndLimitsRefetchesForUnknownKeyIds() throws Exception {
        verifier.verify(good());
        verifier.verify(good());
        assertThat(fetches.get()).isEqualTo(1);
        String unknown = token(key, "unknown-kid", "RS256",
                claims("https://accounts.google.com", CLIENT_ID, NOW.getEpochSecond() + 3600, "true"));
        for (int i = 0; i < 5; i++) {
            assertRejected(unknown, "INVALID_GOOGLE_TOKEN");
        }
        assertThat(fetches.get()).isEqualTo(1);   // the clock is fixed, so no forced refresh is allowed yet
    }

    @Test
    void reportsMissingClientIdAsNotConfigured() throws Exception {
        verifier = verifierFor("");
        assertRejected(good(), "GOOGLE_NOT_CONFIGURED");
        assertThat(fetches.get()).isZero();
    }

    /** A clock the test can move forward. */
    static class MutableClock extends Clock {
        Instant now = NOW;
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        void advance(Duration d) { now = now.plus(d); }
    }

    String tokenWithHd(String hd) throws Exception {
        return token(key, "kid-1", "RS256", "{\"iss\":\"https://accounts.google.com\",\"aud\":\"" + CLIENT_ID + "\",\"exp\":"
                + (NOW.getEpochSecond() + 3600) + ",\"sub\":\"g-1\",\"email\":\"a@" + hd + "\",\"email_verified\":true,\"hd\":\"" + hd + "\"}");
    }

    @Test
    void exposesTheWorkspaceDomainAndWhetherGoogleControlsTheAddress() throws Exception {
        var workspace = verifier.verify(tokenWithHd("columbia.edu"));
        assertThat(workspace.hd()).isEqualTo("columbia.edu");
        assertThat(workspace.emailIsAuthoritative()).isTrue();
        assertThat(verifier.verify(good()).hd()).isEmpty();
        assertThat(verifier.verify(good()).emailIsAuthoritative()).isFalse();   // Ann@Example.com, no hd
        assertThat(new GoogleIdTokenVerifier.GoogleIdentity("s", "Bob@Gmail.com", "Bob", "").emailIsAuthoritative()).isTrue();
    }

    @Test
    void refetchesKeysAfterTheCacheExpires() throws Exception {
        MutableClock clock = new MutableClock();
        var v = new GoogleJwksVerifier(CLIENT_ID, clock, () -> {
            fetches.incrementAndGet();
            return Map.of("kid-1", key.getPublic());
        });
        String longLived = token(key, "kid-1", "RS256",
                claims("https://accounts.google.com", CLIENT_ID, NOW.getEpochSecond() + 7200, "true"));
        v.verify(longLived);
        clock.advance(Duration.ofMinutes(59));
        v.verify(longLived);
        assertThat(fetches.get()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(2));
        v.verify(longLived);
        assertThat(fetches.get()).isEqualTo(2);
    }

    @Test
    void picksUpRotatedKeysButNotMoreOftenThanOnceAMinute() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicReference<Map<String, PublicKey>> published = new AtomicReference<>(Map.of("kid-1", key.getPublic()));
        var v = new GoogleJwksVerifier(CLIENT_ID, clock, () -> {
            fetches.incrementAndGet();
            return published.get();
        });
        String rotated = token(otherKey, "kid-2", "RS256",
                claims("https://accounts.google.com", CLIENT_ID, NOW.getEpochSecond() + 7200, "true"));
        v.verify(good());                                    // first fetch: only kid-1
        published.set(Map.of("kid-1", key.getPublic(), "kid-2", otherKey.getPublic()));   // Google rotates
        assertThatThrownBy(() -> v.verify(rotated)).isInstanceOf(ApiException.class);     // too soon to refetch
        assertThat(fetches.get()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(2));
        assertThat(v.verify(rotated).sub()).isEqualTo("g-123");                           // refetched, now known
        assertThat(fetches.get()).isEqualTo(2);
    }

    @Test
    void backsOffAfterAFailedFetchInsteadOfRetryingForEveryRequest() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicReference<Boolean> googleDown = new AtomicReference<>(true);
        var v = new GoogleJwksVerifier(CLIENT_ID, clock, () -> {
            fetches.incrementAndGet();
            if (googleDown.get()) {
                throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_UNAVAILABLE", "down");
            }
            return Map.of("kid-1", key.getPublic());
        });
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> v.verify(good())).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo("GOOGLE_UNAVAILABLE"));
        }
        assertThat(fetches.get()).isEqualTo(1);              // five of the six failed fast, without calling Google
        googleDown.set(false);
        clock.advance(Duration.ofSeconds(11));
        assertThat(v.verify(good()).sub()).isEqualTo("g-123");
        assertThat(fetches.get()).isEqualTo(2);
    }

    @Test
    void aFailedForcedRefreshStillCountsTowardsTheOncePerMinuteLimit() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicReference<Boolean> googleDown = new AtomicReference<>(false);
        var v = new GoogleJwksVerifier(CLIENT_ID, clock, () -> {
            fetches.incrementAndGet();
            if (googleDown.get()) {
                throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_UNAVAILABLE", "down");
            }
            return Map.of("kid-1", key.getPublic());
        });
        String forged = token(otherKey, "forged-kid", "RS256",
                claims("https://accounts.google.com", CLIENT_ID, NOW.getEpochSecond() + 7200, "true"));
        v.verify(good());                                    // fetch 1 succeeds
        googleDown.set(true);
        clock.advance(Duration.ofMinutes(2));
        assertThatThrownBy(() -> v.verify(forged)).isInstanceOf(ApiException.class);   // fetch 2: forced refresh, fails
        assertThat(fetches.get()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(11));               // the 10 s failure backoff is over, a minute is not
        assertRejected(v, forged, "INVALID_GOOGLE_TOKEN");   // a plain rejection, with no call to Google
        assertThat(fetches.get()).isEqualTo(2);
        clock.advance(Duration.ofMinutes(1));
        assertThatThrownBy(() -> v.verify(forged)).isInstanceOf(ApiException.class);   // a minute after the last attempt
        assertThat(fetches.get()).isEqualTo(3);
    }
}
