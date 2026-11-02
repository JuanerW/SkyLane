package com.skylane.auth;

import com.skylane.auth.AuthDtos.AuthResponse;
import com.skylane.auth.AuthDtos.LoginRequest;
import com.skylane.auth.AuthDtos.RegisterRequest;
import com.skylane.auth.AuthDtos.UserView;
import com.skylane.common.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Profile("!memory")
public class UserService {
    private record Credential(UUID id, int tokenVersion, String hash) {
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public UserService(JdbcTemplate jdbc, PasswordEncoder encoder, JwtService jwt) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        UUID id;
        try {
            id = jdbc.queryForObject("INSERT INTO app_user (email, display_name) VALUES (?, ?) RETURNING id",
                    UUID.class, email, req.displayName().trim());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "An account with this email already exists");
        }
        jdbc.update("INSERT INTO user_credential (user_id, password_hash) VALUES (?, ?)", id, encoder.encode(req.password()));
        return new AuthResponse(jwt.issue(id, 0), me(id));
    }

    public AuthResponse login(LoginRequest req) {
        List<Credential> rows = jdbc.query("""
                        SELECT u.id, u.token_version, c.password_hash FROM app_user u
                        JOIN user_credential c ON c.user_id = u.id
                        WHERE u.email = ? AND u.disabled_at IS NULL""",
                (rs, i) -> new Credential(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3)),
                req.email().trim().toLowerCase(Locale.ROOT));
        if (rows.isEmpty() || !encoder.matches(req.password(), rows.get(0).hash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Wrong email or password");
        }
        Credential c = rows.get(0);
        return new AuthResponse(jwt.issue(c.id(), c.tokenVersion()), me(c.id()));
    }

    public UserView me(UUID id) {
        List<UserView> users = jdbc.query("""
                        SELECT u.id, u.email, u.display_name, u.google_sub, u.created_at,
                               EXISTS (SELECT 1 FROM user_credential c WHERE c.user_id = u.id) AS has_password
                        FROM app_user u WHERE u.id = ?""",
                (rs, i) -> new UserView(rs.getString("id"), rs.getString("email"), rs.getString("display_name"),
                        rs.getBoolean("has_password"), rs.getString("google_sub") != null,
                        rs.getTimestamp("created_at").toInstant()), id);
        if (users.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Login required");
        }
        return users.get(0);
    }
}
