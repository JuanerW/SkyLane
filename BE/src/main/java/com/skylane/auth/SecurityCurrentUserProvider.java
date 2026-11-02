package com.skylane.auth;

import com.skylane.common.ApiException;
import com.skylane.quote.CurrentUserProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** The logged-in user set by JwtAuthFilter. The 'memory' profile keeps its fixed fake user instead. */
@Component
@Profile("!memory")
public class SecurityCurrentUserProvider implements CurrentUserProvider {
    @Override
    public UUID currentUserId() {
        UUID id = JwtAuthFilter.currentUserIdOrNull();
        if (id == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Login required");
        }
        return id;
    }
}
