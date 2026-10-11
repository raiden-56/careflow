package com.careflow.hms.platform;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records the last run of every {@code @Scheduled} method in this JVM (start time, duration, outcome)
 * so the platform health page can show background-job liveness. In-memory only; resets on restart.
 */
@Aspect
@Component
public class ScheduledJobMonitor {

    public record JobRun(String job, Instant lastStartedAt, long lastDurationMs, boolean lastSucceeded, String lastError,
                         long runs, long failures) {
    }

    private final Map<String, JobRun> runs = new ConcurrentHashMap<>();

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object track(ProceedingJoinPoint pjp) throws Throwable {
        String name = pjp.getSignature().getDeclaringType().getSimpleName() + "." + pjp.getSignature().getName();
        Instant start = Instant.now();
        try {
            Object result = pjp.proceed();
            record(name, start, true, null);
            return result;
        } catch (Throwable t) {
            record(name, start, false, t.getClass().getSimpleName());
            throw t;
        }
    }

    private void record(String name, Instant start, boolean ok, String error) {
        long ms = Duration.between(start, Instant.now()).toMillis();
        runs.compute(name, (k, prev) -> new JobRun(k, start, ms, ok, error,
                prev == null ? 1 : prev.runs() + 1, (prev == null ? 0 : prev.failures()) + (ok ? 0 : 1)));
    }

    public List<JobRun> snapshot() {
        return runs.values().stream().sorted(Comparator.comparing(JobRun::job)).toList();
    }
}
