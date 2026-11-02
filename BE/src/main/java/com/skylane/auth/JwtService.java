package com.skylane.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** HS256 JWT with claims sub (user id), ver (token_version), iat, exp (D2, D5). */
@Service
public class JwtService {
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String HEADER =
            B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    public record Claims(UUID userId, int tokenVersion) {
    }

    private final byte[] secret;
    private final Duration ttl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;

    public JwtService(@Value("${skylane.jwt.secret:}") String secret,
                      @Value("${skylane.jwt.ttl-days:7}") int ttlDays, Clock clock) {
        if (secret == null || secret.isBlank()) {
            // No JWT_SECRET: random per start, so logins reset on restart. Fine locally, never in prod.
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
            log.warn("JWT_SECRET is not set; using a random secret (logins reset on restart)");
        } else {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }
        this.ttl = Duration.ofDays(ttlDays);
        this.clock = clock;
    }

    public String issue(UUID userId, int tokenVersion) {
        long now = clock.instant().getEpochSecond();
        Map<String, Object> claims = Map.of("sub", userId.toString(), "ver", tokenVersion,
                "iat", now, "exp", now + ttl.toSeconds());
        try {
            String signingInput = HEADER + "." + B64.encodeToString(mapper.writeValueAsBytes(claims));
            return signingInput + "." + B64.encodeToString(sign(signingInput));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Empty for a malformed, forged or expired token. */
    public Optional<Claims> verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3 || !parts[0].equals(HEADER)) return Optional.empty();
            byte[] expected = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, B64D.decode(parts[2]))) return Optional.empty();
            Map<?, ?> claims = mapper.readValue(B64D.decode(parts[1]), Map.class);
            if (clock.instant().getEpochSecond() >= ((Number) claims.get("exp")).longValue()) return Optional.empty();
            return Optional.of(new Claims(UUID.fromString((String) claims.get("sub")),
                    ((Number) claims.get("ver")).intValue()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    }
}
