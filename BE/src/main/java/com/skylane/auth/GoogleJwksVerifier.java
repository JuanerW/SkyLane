package com.skylane.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skylane.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Verifies a Google ID token (RS256) against Google's published signing keys, with no extra dependency.
 * Checks signature, iss, aud (our client id), exp and email_verified. Keys are cached for an hour.
 */
@Component
@Profile("!memory")
public class GoogleJwksVerifier implements GoogleIdTokenVerifier {
    static final String CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");
    private static final Duration KEY_TTL = Duration.ofHours(1);
    private static final Duration SKEW = Duration.ofSeconds(60);
    private static final Duration FORCED_REFRESH_GAP = Duration.ofMinutes(1);
    private static final Duration FAILURE_BACKOFF = Duration.ofSeconds(10);
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final String clientId;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Supplier<Map<String, PublicKey>> keySource;
    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile Instant keysLoadedAt = Instant.MIN;
    private volatile Instant lastAttemptAt = Instant.MIN;
    private volatile Instant lastFailureAt = Instant.MIN;

    @Autowired
    public GoogleJwksVerifier(@Value("${skylane.google.client-id:}") String clientId, Clock clock) {
        this(clientId, clock, null);
    }

    /** Tests pass their own key source so nothing touches the network. */
    GoogleJwksVerifier(String clientId, Clock clock, Supplier<Map<String, PublicKey>> keySource) {
        this.clientId = clientId;
        this.clock = clock;
        this.keySource = keySource != null ? keySource : this::fetchKeys;
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        if (clientId == null || clientId.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_NOT_CONFIGURED", "Google sign-in is not configured");
        }
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length != 3) throw invalid();
            JsonNode header = mapper.readTree(B64D.decode(parts[0]));
            if (!"RS256".equals(header.path("alg").asText())) throw invalid();
            PublicKey key = keyFor(header.path("kid").asText());
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(B64D.decode(parts[2]))) throw invalid();

            JsonNode c = mapper.readTree(B64D.decode(parts[1]));
            if (!ISSUERS.contains(c.path("iss").asText()) || !clientId.equals(c.path("aud").asText())) throw invalid();
            if (clock.instant().minus(SKEW).getEpochSecond() >= c.path("exp").asLong(0)) throw invalid();
            // email_verified is a boolean in current tokens and the string "true" in older ones
            if (!"true".equals(c.path("email_verified").asText())) throw invalid();
            String sub = c.path("sub").asText("");
            String email = c.path("email").asText("");
            if (sub.isBlank() || email.isBlank()) throw invalid();
            return new GoogleIdentity(sub, email, c.path("name").asText(""), c.path("hd").asText(""));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    private PublicKey keyFor(String kid) {
        boolean fresh = keysLoadedAt.plus(KEY_TTL).isAfter(clock.instant());
        if (fresh) {
            PublicKey cached = keys.get(kid);
            if (cached != null) return cached;   // the normal path never takes the lock or touches the network
        }
        // stale cache, or a kid we have not seen (Google rotated keys): refresh, rate limited
        PublicKey key = refresh(fresh).get(kid);
        if (key == null) throw invalid();
        return key;
    }

    /**
     * Only one thread talks to Google at a time. A failed fetch is remembered, so the threads queued behind it
     * (and a flood of forged tokens) fail fast for a few seconds instead of each waiting out the timeout.
     * unknownKid = the cache is fresh but does not know this kid: refresh at most once a minute, counting failed
     * attempts too. Otherwise the cache has simply expired: refresh, subject only to the failure backoff.
     */
    private synchronized Map<String, PublicKey> refresh(boolean unknownKid) {
        Instant now = clock.instant();
        if (unknownKid) {
            if (lastAttemptAt.plus(FORCED_REFRESH_GAP).isAfter(now)) return keys;
        } else if (keysLoadedAt.plus(KEY_TTL).isAfter(now)) {
            return keys;   // another thread refreshed while we waited for the lock
        }
        if (lastFailureAt.plus(FAILURE_BACKOFF).isAfter(now)) throw unavailable();
        lastAttemptAt = now;
        try {
            keys = keySource.get();
            keysLoadedAt = now;
        } catch (RuntimeException e) {
            lastFailureAt = now;
            throw e;
        }
        return keys;
    }

    private Map<String, PublicKey> fetchKeys() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(CERTS_URL)).timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
            Map<String, PublicKey> out = new HashMap<>();
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            for (JsonNode k : mapper.readTree(res.body()).path("keys")) {
                if (!"RSA".equals(k.path("kty").asText())) continue;
                out.put(k.path("kid").asText(), rsa.generatePublic(new RSAPublicKeySpec(
                        new BigInteger(1, B64D.decode(k.path("n").asText())),
                        new BigInteger(1, B64D.decode(k.path("e").asText())))));
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception e) {
            throw unavailable();
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN", "Google sign-in could not be verified");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_UNAVAILABLE", "Could not reach Google to verify sign-in");
    }
}
