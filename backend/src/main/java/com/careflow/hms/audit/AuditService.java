package com.careflow.hms.audit;

import com.careflow.hms.config.TraceIdFilter;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes audit events in an independent transaction so that DENIED/FAILED attempts are recorded
 * even when the surrounding business transaction rolls back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    /**
     * Set only while the demo seeder bulk-loads data inside a single uncommitted transaction, where
     * independent audit transactions could not reference the not-yet-committed rows.
     */
    private static final ThreadLocal<Boolean> SUPPRESSED = new ThreadLocal<>();

    private final AuditEventRepository repository;

    public static void runSuppressed(Runnable work) {
        SUPPRESSED.set(Boolean.TRUE);
        try {
            work.run();
        } finally {
            SUPPRESSED.remove();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String entityType, UUID entityId, UUID patientId, String summary) {
        record(action, entityType, entityId, patientId, AuditEvent.Outcome.SUCCESS, summary, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String entityType, UUID entityId, UUID patientId,
                       AuditEvent.Outcome outcome, String summary, Map<String, Object> details) {
        if (Boolean.TRUE.equals(SUPPRESSED.get())) {
            return;
        }
        try {
            AuditEvent e = new AuditEvent();
            Optional<CurrentUser> cu = TenantContext.currentUser();
            cu.ifPresent(u -> {
                e.setOrganizationId(u.organizationId());
                e.setActorUserId(u.userId());
                e.setActorName(u.fullName());
                e.setActorRole(u.role().name());
            });
            if (cu.isEmpty()) {
                try {
                    e.setOrganizationId(TenantContext.requireOrganizationId());
                } catch (IllegalStateException ignored) {
                    // platform-level event without tenant
                }
                e.setActorUserId(TenantContext.actorId());
                e.setActorRole("SYSTEM");
                e.setActorName("System");
            }
            e.setAction(action);
            e.setEntityType(entityType);
            e.setEntityId(entityId);
            e.setPatientId(patientId);
            e.setOutcome(outcome);
            e.setSummary(summary == null ? null : summary.length() > 1000 ? summary.substring(0, 1000) : summary);
            e.setDetails(details);
            e.setIpAddress(clientIp());
            e.setTraceId(MDC.get(TraceIdFilter.MDC_KEY));
            repository.save(e);
        } catch (RuntimeException ex) {
            // Auditing must never break the request, but failures must be visible to operators.
            log.error("Failed to write audit event {} {}", action, entityId, ex);
        }
    }

    /** Records an explicit audit event for a known actor (e.g. login attempts before authentication). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFor(UUID orgId, UUID actorId, String actorName, String actorRole, String action,
                          AuditEvent.Outcome outcome, String summary) {
        if (Boolean.TRUE.equals(SUPPRESSED.get())) {
            return;
        }
        AuditEvent e = new AuditEvent();
        e.setOrganizationId(orgId);
        e.setActorUserId(actorId);
        e.setActorName(actorName);
        e.setActorRole(actorRole);
        e.setAction(action);
        e.setEntityType("USER");
        e.setEntityId(actorId);
        e.setOutcome(outcome);
        e.setSummary(summary);
        e.setIpAddress(clientIp());
        e.setTraceId(MDC.get(TraceIdFilter.MDC_KEY));
        repository.save(e);
    }

    public static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            return req.getRemoteAddr();
        }
        return null;
    }
}
