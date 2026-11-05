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
import java.util.concurrent.atomic.AtomicInteger;

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
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOfSatisfying(ApiException.class,
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
}
