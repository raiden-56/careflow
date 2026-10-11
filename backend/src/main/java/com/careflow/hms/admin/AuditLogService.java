package com.careflow.hms.admin;

import com.careflow.hms.audit.AuditEvent;
import com.careflow.hms.audit.AuditEventRepository;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Organization-scoped audit log search, detail and CSV export (AUDIT_READ). */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    static final int EXPORT_LIMIT = 10_000;
    private static final Map<String, String> SORTS = Map.of("occurredAt", "occurredAt", "action", "action",
            "actor", "actorName", "outcome", "outcome");

    private final AuditEventRepository repository;
    private final PatientRepository patients;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public record AuditFilter(String actor, String patientMrn, String action, String entityType,
                              AuditEvent.Outcome outcome, LocalDate from, LocalDate to) {
    }

    public record AuditRow(UUID id, Instant occurredAt, String actorName, String actorRole, String action, String entityType,
                           UUID entityId, String patientMrn, AuditEvent.Outcome outcome, String summary, String ipAddress,
                           boolean hasDetails) {
    }

    public record AuditDetail(UUID id, Instant occurredAt, UUID actorUserId, String actorName, String actorRole, String action,
                              String entityType, UUID entityId, UUID patientId, String patientMrn, String patientName,
                              AuditEvent.Outcome outcome, String summary, Map<String, Object> details, String ipAddress,
                              String traceId) {
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditRow> search(AuditFilter f, int page, int size, String sort) {
        Page<AuditEvent> result = query(f, Paging.of(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "occurredAt")));
        Map<UUID, String> mrns = mrns(result.getContent());
        return PageResponse.of(result, e -> new AuditRow(e.getId(), e.getOccurredAt(), e.getActorName(), e.getActorRole(),
                e.getAction(), e.getEntityType(), e.getEntityId(), e.getPatientId() == null ? null : mrns.get(e.getPatientId()),
                e.getOutcome(), e.getSummary(), e.getIpAddress(), e.getDetails() != null && !e.getDetails().isEmpty()));
    }

    @Transactional(readOnly = true)
    public AuditDetail get(UUID id) {
        UUID org = TenantContext.requireOrganizationId();
        AuditEvent e = repository.findById(id).filter(x -> org.equals(x.getOrganizationId()))
                .orElseThrow(() -> ApiException.notFound("Audit event"));
        Patient p = e.getPatientId() == null ? null : patients.findByIdAndOrganizationId(e.getPatientId(), org).orElse(null);
        return new AuditDetail(e.getId(), e.getOccurredAt(), e.getActorUserId(), e.getActorName(), e.getActorRole(), e.getAction(),
                e.getEntityType(), e.getEntityId(), e.getPatientId(), p == null ? null : p.getMrn(), p == null ? null : p.fullName(),
                e.getOutcome(), e.getSummary(), e.getDetails(), e.getIpAddress(), e.getTraceId());
    }

    @Transactional(readOnly = true)
    public List<String> actions() {
        return repository.distinctActions(TenantContext.requireOrganizationId());
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv(AuditFilter f) {
        Page<AuditEvent> result = query(f, PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, "occurredAt")));
        Map<UUID, String> mrns = mrns(result.getContent());
        ZoneId zone = TimeUtil.zone();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder("﻿");
        ReportExporter.line(sb, List.of("Occurred (" + zone.getId() + ")", "Actor", "Role", "Action", "Entity type", "Entity id",
                "Patient MRN", "Outcome", "Summary", "IP address", "Trace id"));
        for (AuditEvent e : result.getContent()) {
            ReportExporter.line(sb, java.util.Arrays.asList(fmt.format(e.getOccurredAt().atZone(zone)), e.getActorName(),
                    e.getActorRole(), e.getAction(), e.getEntityType(), e.getEntityId(),
                    e.getPatientId() == null ? null : mrns.get(e.getPatientId()), e.getOutcome(), e.getSummary(),
                    e.getIpAddress(), e.getTraceId()));
        }
        audit.record("AUDIT_EXPORTED", "AUDIT", null, null, AuditEvent.Outcome.SUCCESS,
                "Exported " + result.getNumberOfElements() + " audit events", filterDetails(f));
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Page<AuditEvent> query(AuditFilter f, org.springframework.data.domain.Pageable pageable) {
        UUID org = TenantContext.requireOrganizationId();
        ZoneId zone = TimeUtil.zone();
        LocalDate to = f.to() == null ? LocalDate.now(zone) : f.to();
        LocalDate from = f.from() == null ? to.minusDays(30) : f.from();
        if (from.isAfter(to)) {
            throw ApiException.badRequest("INVALID_RANGE", "The start date must be on or before the end date.");
        }
        UUID patientId = null;
        if (f.patientMrn() != null && !f.patientMrn().isBlank()) {
            List<UUID> ids = jdbc.queryForList("SELECT id FROM patients WHERE organization_id = ? AND upper(mrn) = upper(?)",
                    UUID.class, org, f.patientMrn().trim());
            if (ids.isEmpty()) {
                // Unknown MRN: an impossible id yields an empty page rather than an error.
                patientId = new UUID(0, 0);
            } else {
                patientId = ids.get(0);
            }
        }
        return repository.searchOrganization(org, null, Paging.likePattern(f.actor()), patientId,
                blank(f.action()), blank(f.entityType()), f.outcome(),
                from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant(), pageable);
    }

    private Map<UUID, String> mrns(List<AuditEvent> events) {
        List<UUID> ids = events.stream().map(AuditEvent::getPatientId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, String> m = new HashMap<>();
        if (!ids.isEmpty()) {
            patients.findByIdIn(ids).forEach(p -> m.put(p.getId(), p.getMrn()));
        }
        return m;
    }

    private static Map<String, Object> filterDetails(AuditFilter f) {
        Map<String, Object> m = new HashMap<>();
        if (f.actor() != null) m.put("actor", f.actor());
        if (f.patientMrn() != null) m.put("patientMrn", f.patientMrn());
        if (f.action() != null) m.put("action", f.action());
        if (f.outcome() != null) m.put("outcome", f.outcome().name());
        if (f.from() != null) m.put("from", f.from().toString());
        if (f.to() != null) m.put("to", f.to().toString());
        return m;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
