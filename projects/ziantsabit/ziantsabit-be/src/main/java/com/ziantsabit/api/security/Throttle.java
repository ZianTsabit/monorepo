package com.ziantsabit.api.security;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.ziantsabit.api.exception.Throttled;

import jakarta.servlet.http.HttpServletRequest;

/**
 * A per-caller sliding-window rate limit, the same algorithm as DRF's
 * ScopedRateThrottle: remember when each of the caller's recent requests
 * arrived, and refuse one that would make more than {@code count} inside the
 * window.
 *
 * <p>Applied only to the two routes a stranger can write to -- posting a
 * comment and the reaction bar -- and keyed on the user for the owner and the
 * client address for everyone else. That address is Tomcat's view of it, which
 * behind a proxy is the client's only when {@code FORWARD_HEADERS_STRATEGY} is
 * {@code native}; otherwise every visitor shares the proxy's address and
 * therefore one budget.
 *
 * <p>In memory, so the counts reset on restart. That is the same trade DRF's
 * per-process cache made: a brake on a script, not a defence against a
 * determined one -- moderation in the admin console is what deals with a bad
 * comment.
 */
public final class Throttle {

    private final String scope;
    private final int count;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Deque<Long>> history = new ConcurrentHashMap<>();

    public Throttle(String scope, String rate, Clock clock) {
        this.scope = scope;
        this.clock = clock;
        String[] parts = rate.strip().split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("A rate is '<count>/<period>', got '" + rate + "'");
        }
        this.count = Integer.parseInt(parts[0].strip());
        this.windowMillis = switch (parts[1].strip().toLowerCase(Locale.ROOT).charAt(0)) {
            case 's' -> 1_000L;
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            default -> throw new IllegalArgumentException("Unknown period in rate '" + rate + "'");
        };
    }

    /** Count this request against the caller, or throw a 429 if it is one too many. */
    public void check(HttpServletRequest request) {
        long now = clock.millis();
        Deque<Long> calls = history.computeIfAbsent(scope + ":" + ident(request), key -> new ArrayDeque<>());
        synchronized (calls) {
            while (!calls.isEmpty() && calls.peekLast() <= now - windowMillis) {
                calls.removeLast();
            }
            if (calls.size() >= count) {
                long oldest = calls.peekLast();
                long remaining = Math.max(0, windowMillis - (now - oldest));
                throw new Throttled((remaining + 999) / 1000);
            }
            calls.addFirst(now);
        }
    }

    /** Forget every caller -- for tests, which share one application context. */
    public void reset() {
        history.clear();
    }

    private static String ident(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            return "user:" + auth.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
