package com.vault.security;

/** The signed-in account behind a request. */
public record UserPrincipal(String userId, String email) {
}
