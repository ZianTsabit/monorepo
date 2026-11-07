package com.ziantsabit.api.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The {@code app.*} block of application.yml. Lists are space-separated, as Django's were. */
@ConfigurationProperties("app")
public record AppProperties(
        String allowedHosts,
        String corsAllowedOrigins,
        String csrfTrustedOrigins,
        String csrfCookieSameSite,
        boolean csrfCookieSecure,
        String commentRateLimit,
        String reactionRateLimit,
        long maxUploadSize,
        String adminUsername,
        String adminPassword,
        int passwordIterations,
        Storage storage) {

    public record Storage(
            String bucket, String accessKey, String secretKey, String region, String endpoint,
            String publicEndpoint) {
    }

    public List<String> allowedHostList() {
        return split(allowedHosts);
    }

    public List<String> corsAllowedOriginList() {
        return split(corsAllowedOrigins);
    }

    public List<String> csrfTrustedOriginList() {
        return split(csrfTrustedOrigins);
    }

    private static List<String> split(String value) {
        return value == null ? List.of()
                : Arrays.stream(value.trim().split("\\s+")).filter(item -> !item.isEmpty()).toList();
    }
}
