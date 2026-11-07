package com.ziantsabit.api.model;

/** One row of Django's {@code auth_user}, as much of it as this API reads. */
public record UserAccount(String username, String passwordHash, boolean active) {
}
