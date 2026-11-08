package com.ziantsabit.api.model;

/** One row of {@code app_user}: the owner's login. */
public record UserAccount(String username, String passwordHash, boolean enabled) {
}
