package com.ziantsabit.api.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who is asking, as the content rules need to know it: the owner, logged in by
 * session or basic auth, or an anonymous visitor.
 *
 * <p>The distinction runs through every read in the API -- drafts, hidden
 * comments and the admin-only filters exist only for the owner -- so it lives
 * in one place rather than as a repeated instanceof check.
 */
public final class Viewer {

    private Viewer() {
    }

    public static boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    public static String username() {
        return isAuthenticated() ? SecurityContextHolder.getContext().getAuthentication().getName() : null;
    }
}
