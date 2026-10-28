package com.skylane.quote;

import com.skylane.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Dev only (profile 'dev'): acts as the seeded test user. Never active in any other profile. */
@Component
@Profile("dev")
public class DevCurrentUserProvider implements CurrentUserProvider {
    private final JdbcTemplate jdbc;
    private final String email;

    public DevCurrentUserProvider(JdbcTemplate jdbc, @Value("${skylane.dev.user-email}") String email) {
        this.jdbc = jdbc;
        this.email = email;
    }

    @Override
    public UUID currentUserId() {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM app_user WHERE email = ?", UUID.class, email);
        if (ids.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "No test user " + email + " (run database/dev-seed-quote.sql)");
        }
        return ids.get(0);
    }
}
