package com.careflow.hms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "careflow")
public record CareFlowProperties(
        Security security,
        Storage storage,
        String frontendBaseUrl,
        Demo demo,
        Dev dev) {

    public record Security(String jwtSecret, Duration accessTokenTtl, Duration refreshTokenTtl,
                           boolean cookieSecure, Login login) {
    }

    public record Login(int maxFailedAttempts, Duration lockoutDuration, int rateLimitPerMinute) {
    }

    public record Storage(String uploadDir, Duration downloadTokenTtl) {
    }

    public record Demo(boolean enabled) {
    }

    public record Dev(boolean outboxEnabled) {
    }
}
