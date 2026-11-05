package com.skylane.auth;

import java.util.Locale;

/** Checks a Google ID token and returns who it belongs to. Throws ApiException when it cannot be trusted. */
public interface GoogleIdTokenVerifier {
    /** hd is the Google Workspace domain claim; blank for a personal account. */
    record GoogleIdentity(String sub, String email, String name, String hd) {
        /**
         * True when Google itself controls this address (a gmail.com account or a Workspace domain), so it cannot
         * have been recycled to a new owner. Any other address can show email_verified = true for a previous
         * owner, so it must not be used to take over an existing account.
         */
        public boolean emailIsAuthoritative() {
            return (hd != null && !hd.isBlank()) || email.toLowerCase(Locale.ROOT).endsWith("@gmail.com");
        }
    }

    GoogleIdentity verify(String idToken);
}
