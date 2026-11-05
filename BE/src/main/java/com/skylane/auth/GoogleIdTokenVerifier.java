package com.skylane.auth;

/** Checks a Google ID token and returns who it belongs to. Throws ApiException when it cannot be trusted. */
public interface GoogleIdTokenVerifier {
    record GoogleIdentity(String sub, String email, String name) {
    }

    GoogleIdentity verify(String idToken);
}
