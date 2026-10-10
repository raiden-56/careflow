package com.careflow.hms.security;

import com.careflow.hms.config.CareFlowProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limiter for authentication endpoints, keyed by client IP (and separately by
 * account identifier). In-memory: suitable for a single instance; use a shared store when scaling out.
 */
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    private static final long WINDOW_MS = 60_000;

    private final CareFlowProperties props;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /** Returns false when the key exceeded the configured attempts per minute. */
    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) {
                q.pollFirst();
            }
            if (q.size() >= props.security().login().rateLimitPerMinute()) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty() || now - e.getValue().peekLast() > WINDOW_MS;
            }
        });
    }
}
