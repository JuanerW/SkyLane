package com.skylane.auth;

import com.skylane.auth.AuthDtos.AuthResponse;
import com.skylane.auth.AuthDtos.ChangePasswordRequest;
import com.skylane.auth.AuthDtos.LoginRequest;
import com.skylane.auth.AuthDtos.RegisterRequest;
import com.skylane.auth.AuthDtos.UpdateProfileRequest;
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
    private record Credential(UUID id, int tokenVersion, String hash, boolean disabled) {
    }

    private record Account(UUID id, int tokenVersion, boolean disabled) {
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final GoogleIdTokenVerifier google;

    public UserService(JdbcTemplate jdbc, PasswordEncoder encoder, JwtService jwt, GoogleIdTokenVerifier google) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.jwt = jwt;
        this.google = google;
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
                        SELECT u.id, u.token_version, c.password_hash, u.disabled_at IS NOT NULL FROM app_user u
                        JOIN user_credential c ON c.user_id = u.id
                        WHERE u.email = ?""",
                (rs, i) -> new Credential(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getBoolean(4)),
                req.email().trim().toLowerCase(Locale.ROOT));
        if (rows.isEmpty() || !encoder.matches(req.password(), rows.get(0).hash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Wrong email or password");
        }
        Credential c = rows.get(0);
        if (c.disabled()) {
            throw disabled();
        }
        return new AuthResponse(jwt.issue(c.id(), c.tokenVersion()), me(c.id()));
    }

    /**
     * Google sign-in. Match by google_sub first; otherwise by email (Google vouches for the address via
     * email_verified, so an existing password account is linked); otherwise create an account with no password.
     */
    @Transactional
    public AuthResponse googleLogin(String idToken) {
        GoogleIdTokenVerifier.GoogleIdentity g = google.verify(idToken);
        String email = g.email().trim().toLowerCase(Locale.ROOT);
        Account account = find("u.google_sub = ?", g.sub());
        if (account == null) {
            account = find("u.email = ?", email);
            if (account != null && jdbc.update(
                    "UPDATE app_user SET google_sub = ?, updated_at = now() WHERE id = ? AND google_sub IS NULL",
                    g.sub(), account.id()) == 0) {
                // the email already belongs to an account linked to a different Google identity
                throw new ApiException(HttpStatus.CONFLICT, "GOOGLE_ACCOUNT_MISMATCH",
                        "This email is linked to a different Google account");
            }
        }
        if (account == null) {
            try {
                UUID id = jdbc.queryForObject(
                        "INSERT INTO app_user (email, display_name, google_sub) VALUES (?, ?, ?) RETURNING id",
                        UUID.class, email, googleDisplayName(g.name(), email), g.sub());
                account = new Account(id, 0, false);
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "An account with this email already exists");
            }
        }
        if (account.disabled()) {
            throw disabled();
        }
        return new AuthResponse(jwt.issue(account.id(), account.tokenVersion()), me(account.id()));
    }

    @Transactional
    public UserView updateProfile(UUID id, UpdateProfileRequest req) {
        String name = req.displayName().trim();
        if (name.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "display_name must not be blank");
        }
        jdbc.update("UPDATE app_user SET display_name = ?, updated_at = now() WHERE id = ?", name, id);
        return me(id);
    }

    /**
     * Sets a new password and bumps token_version, which signs every other device out. The caller gets a fresh
     * token so their own session survives. An account with no password yet (Google sign-up) may set one
     * without giving a current password.
     */
    @Transactional
    public AuthResponse changePassword(UUID id, ChangePasswordRequest req) {
        List<String> hashes = jdbc.queryForList("SELECT password_hash FROM user_credential WHERE user_id = ?",
                String.class, id);
        if (!hashes.isEmpty() && (req.currentPassword() == null || !encoder.matches(req.currentPassword(), hashes.get(0)))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURRENT_PASSWORD", "Current password is incorrect");
        }
        jdbc.update("""
                INSERT INTO user_credential (user_id, password_hash) VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET password_hash = EXCLUDED.password_hash, updated_at = now()""",
                id, encoder.encode(req.newPassword()));
        Integer version = jdbc.queryForObject(
                "UPDATE app_user SET token_version = token_version + 1, updated_at = now() WHERE id = ? RETURNING token_version",
                Integer.class, id);
        return new AuthResponse(jwt.issue(id, version), me(id));
    }

    private Account find(String where, Object arg) {
        List<Account> rows = jdbc.query(
                "SELECT u.id, u.token_version, u.disabled_at IS NOT NULL FROM app_user u WHERE " + where,
                (rs, i) -> new Account(rs.getObject(1, UUID.class), rs.getInt(2), rs.getBoolean(3)), arg);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String googleDisplayName(String name, String email) {
        String base = name == null || name.isBlank() ? email.substring(0, email.indexOf('@')) : name.trim();
        return base.length() > 80 ? base.substring(0, 80) : base;
    }

    private static ApiException disabled() {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "This account has been disabled");
    }

    public UserView me(UUID id) {
        List<UserView> users = jdbc.query("""
                        SELECT u.id, u.email, u.display_name, u.google_sub, u.created_at, u.role, u.operator_id,
                               EXISTS (SELECT 1 FROM user_credential c WHERE c.user_id = u.id) AS has_password
                        FROM app_user u WHERE u.id = ?""",
                (rs, i) -> new UserView(rs.getString("id"), rs.getString("email"), rs.getString("display_name"),
                        rs.getBoolean("has_password"), rs.getString("google_sub") != null,
                        rs.getTimestamp("created_at").toInstant(), rs.getString("role"), rs.getString("operator_id")), id);
        if (users.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Login required");
        }
        return users.get(0);
    }
}
