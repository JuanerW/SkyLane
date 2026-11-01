package com.skylane.quote;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Profile("memory")
public class InMemoryCurrentUserProvider implements CurrentUserProvider {
    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Override
    public UUID currentUserId() {
        return DEV_USER;
    }
}
