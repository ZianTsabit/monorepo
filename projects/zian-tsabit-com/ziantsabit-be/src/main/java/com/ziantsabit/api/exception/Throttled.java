package com.ziantsabit.api.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

/** A 429, carrying the Retry-After the handler sends alongside DRF's message. */
public class Throttled extends ApiException {

    private final long waitSeconds;

    public Throttled(long waitSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, Map.of(
                "detail",
                "Request was throttled. Expected available in " + waitSeconds
                        + (waitSeconds == 1 ? " second." : " seconds.")));
        this.waitSeconds = waitSeconds;
    }

    public long waitSeconds() {
        return waitSeconds;
    }
}
