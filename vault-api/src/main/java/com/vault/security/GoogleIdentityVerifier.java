package com.vault.security;

import java.util.Optional;

/** Turns a Google ID token (the "credential" from Google Sign-In) into a verified identity. */
public interface GoogleIdentityVerifier {

    record Identity(String subject, String email, String name, String picture) {
    }

    /** @return empty if the token is invalid, expired, for another app, or the email is unverified */
    Optional<Identity> verify(String idToken);

    boolean isConfigured();
}
