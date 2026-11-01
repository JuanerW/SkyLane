package com.skylane.quote;

import com.skylane.common.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Default when neither 'dev' nor 'memory' is active: nobody is logged in until the JWT filter exists. */
@Component
@Profile("!dev & !memory")
public class UnauthenticatedCurrentUserProvider implements CurrentUserProvider {
    @Override
    public UUID currentUserId() {
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Login required");
    }
}
