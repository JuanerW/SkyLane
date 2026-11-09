package com.skylane.ops;

import com.skylane.common.ApiException;
import com.skylane.ops.OpsDtos.CreateOperator;
import com.skylane.ops.OpsDtos.CreateUser;
import com.skylane.ops.OpsDtos.OperatorRef;
import com.skylane.ops.OpsDtos.PatchUser;
import com.skylane.ops.OpsDtos.UserRef;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** Design 8.1 (D33): admin creates operator companies and their accounts, disables users, forces logout. */
@Service
@Profile("!memory")
public class AdminService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public AdminService(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    public List<OperatorRef> operators() {
        return jdbc.query("SELECT id, name FROM operator ORDER BY name",
                (rs, i) -> new OperatorRef(rs.getObject("id").toString(), rs.getString("name")));
    }

    public OperatorRef createOperator(CreateOperator req) {
        String name = req.name().trim();
        try {
            UUID id = jdbc.queryForObject("INSERT INTO operator (name) VALUES (?) RETURNING id", UUID.class, name);
            return new OperatorRef(id.toString(), name);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "OPERATOR_NAME_TAKEN", "An operator with this name already exists");
        }
    }

    /** Staff accounts only (operator and admin); customers are not listed. */
    public List<UserRef> users() {
        return jdbc.query("""
                SELECT id, email, display_name, role, operator_id, disabled_at FROM app_user
                WHERE role <> 'customer' ORDER BY role, email""", (rs, i) -> userRef(rs));
    }

    @Transactional
    public UserRef createUser(CreateUser req) {
        UUID operatorId;
        try {
            operatorId = UUID.fromString(req.operatorId());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OPERATOR_NOT_FOUND", "This operator does not exist");
        }
        if (jdbc.queryForList("SELECT 1 FROM operator WHERE id = ?", operatorId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "OPERATOR_NOT_FOUND", "This operator does not exist");
        }
        UUID id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO app_user (email, display_name, role, operator_id)
                    VALUES (?, ?, 'operator', ?) RETURNING id""",
                    UUID.class, req.email().trim(), req.displayName().trim(), operatorId);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "This email is already registered");
        }
        jdbc.update("INSERT INTO user_credential (user_id, password_hash) VALUES (?, ?)", id, encoder.encode(req.password()));
        return user(id);
    }

    @Transactional
    public UserRef patchUser(UUID adminId, UUID id, PatchUser req) {
        if (jdbc.queryForList("SELECT 1 FROM app_user WHERE id = ?", id).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "This user does not exist");
        }
        if (id.equals(adminId) && Boolean.TRUE.equals(req.disabled())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "You cannot disable your own account");
        }
        if (req.disabled() != null) {
            jdbc.update("UPDATE app_user SET disabled_at = CASE WHEN ? THEN now() END, updated_at = now() WHERE id = ?",
                    req.disabled(), id);
        }
        // Disabling also signs the user out everywhere; force_logout alone just bumps token_version.
        if (Boolean.TRUE.equals(req.forceLogout()) || Boolean.TRUE.equals(req.disabled())) {
            jdbc.update("UPDATE app_user SET token_version = token_version + 1, updated_at = now() WHERE id = ?", id);
        }
        return user(id);
    }

    private UserRef user(UUID id) {
        return jdbc.queryForObject(
                "SELECT id, email, display_name, role, operator_id, disabled_at FROM app_user WHERE id = ?",
                (rs, i) -> userRef(rs), id);
    }

    private static UserRef userRef(ResultSet rs) throws SQLException {
        Timestamp d = rs.getTimestamp("disabled_at");
        Object op = rs.getObject("operator_id");
        return new UserRef(rs.getObject("id").toString(), rs.getString("email"), rs.getString("display_name"),
                rs.getString("role"), op == null ? null : op.toString(), d == null ? null : d.toInstant());
    }
}
