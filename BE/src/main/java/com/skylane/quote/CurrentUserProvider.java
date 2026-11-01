package com.skylane.quote;

import java.util.UUID;

/** Who is calling. Replace the dev implementation with the SecurityContext user once the JWT filter lands. */
public interface CurrentUserProvider {
    UUID currentUserId();
}
