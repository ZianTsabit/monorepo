package com.ziantsabit.api.dto;

/** Who the caller is, and the CSRF token their next write has to carry. */
public record SessionState(boolean authenticated, String username, String csrfToken) {
}
