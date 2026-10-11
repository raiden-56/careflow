package com.careflow.hms.admin;

import com.careflow.hms.admin.ConfigDtos.DischargeTemplateRequest;
import com.careflow.hms.admin.ConfigDtos.DischargeTemplateView;
import com.careflow.hms.admin.ConfigDtos.EscalationRequest;
import com.careflow.hms.admin.ConfigDtos.EscalationView;
import com.careflow.hms.admin.ConfigDtos.PreviewRequest;
import com.careflow.hms.admin.ConfigDtos.PreviewResponse;
import com.careflow.hms.admin.ConfigDtos.TemplateRequest;
import com.careflow.hms.admin.ConfigDtos.TemplateView;
import com.careflow.hms.admin.ConfigDtos.ThresholdRequest;
import com.careflow.hms.admin.ConfigDtos.ThresholdView;
import com.careflow.hms.audit.AuditEvent;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.clinical.ThresholdRule;
import com.careflow.hms.clinical.ThresholdRuleRepository;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.discharge.DischargeStepTemplate;
import com.careflow.hms.discharge.DischargeStepTemplateRepository;
import com.careflow.hms.notification.EscalationRule;
import com.careflow.hms.notification.EscalationRuleRepository;
import com.careflow.hms.notification.NotificationTemplate;
import com.careflow.hms.notification.NotificationTemplateRepository;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hospital configuration: notification templates, escalation rules, observation thresholds and the
 * discharge checklist template. Records are deactivated rather than deleted; every change is audited.
 */
@Service
@RequiredArgsConstructor
public class ConfigurationService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_]*)\\s*}}");

    /** Placeholders the notification senders fill in, with sample values for previews. */
    public static final Map<String, String> KNOWN_PLACEHOLDERS = new LinkedHashMap<>();

    static {
        KNOWN_PLACEHOLDERS.put("hospital", "CareFlow General Hospital (Demo)");
        KNOWN_PLACEHOLDERS.put("patient", "Ravi Sharma");
        KNOWN_PLACEHOLDERS.put("title", "Diabetes clinic review");
        KNOWN_PLACEHOLDERS.put("when", "14 Oct 2026, 10:30");
        KNOWN_PLACEHOLDERS.put("location", "OPD Block, Room 12");
        KNOWN_PLACEHOLDERS.put("amount", "₹2,500.00");
        KNOWN_PLACEHOLDERS.put("invoice", "INV-2026-00012");
        KNOWN_PLACEHOLDERS.put("receipt", "RCP-2026-00031");
        KNOWN_PLACEHOLDERS.put("doctor", "Dr. Arjun Mehta");
        KNOWN_PLACEHOLDERS.put("ward", "Medical Ward A");
    }

    /** Parameters accepted by threshold rules, with plausible physiological bounds for validation. */
    private static final Map<ThresholdRule.Parameter, BigDecimal[]> PARAMETER_RANGES = Map.of(
            ThresholdRule.Parameter.TEMPERATURE_C, new BigDecimal[]{new BigDecimal("25"), new BigDecimal("45")},
            ThresholdRule.Parameter.PULSE_BPM, new BigDecimal[]{BigDecimal.ZERO, new BigDecimal("300")},
            ThresholdRule.Parameter.RESPIRATORY_RATE, new BigDecimal[]{BigDecimal.ZERO, new BigDecimal("80")},
            ThresholdRule.Parameter.SYSTOLIC_BP, new BigDecimal[]{BigDecimal.ZERO, new BigDecimal("300")},
            ThresholdRule.Parameter.DIASTOLIC_BP, new BigDecimal[]{BigDecimal.ZERO, new BigDecimal("200")},
            ThresholdRule.Parameter.SPO2, new BigDecimal[]{BigDecimal.ZERO, new BigDecimal("100")},
            ThresholdRule.Parameter.PAIN_SCORE, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.TEN},
            ThresholdRule.Parameter.BLOOD_GLUCOSE, new BigDecimal[]{BigDecimal.ZERO, new BigDecimal("1000")});

    private final NotificationTemplateRepository templates;
    private final EscalationRuleRepository escalations;
    private final ThresholdRuleRepository thresholds;
    private final DischargeStepTemplateRepository dischargeTemplates;
    private final UserRepository users;
    private final AuditService audit;

    // ================================================================== notification templates

    @Transactional(readOnly = true)
    public List<TemplateView> listTemplates() {
        UUID org = TenantContext.requireOrganizationId();
        Map<UUID, String> names = new HashMap<>();
        return templates.findByOrganizationIdOrderByCode(org).stream()
                .sorted((a, b) -> a.getCode().equals(b.getCode()) ? a.getChannel().compareTo(b.getChannel()) : a.getCode().compareTo(b.getCode()))
                .map(t -> toView(t, names)).toList();
    }

    @Transactional
    public TemplateView saveTemplate(UUID id, TemplateRequest r) {
        UUID org = TenantContext.requireOrganizationId();
        NotificationTemplate t = id == null ? new NotificationTemplate() : templates.findById(id)
                .filter(x -> x.getOrganizationId().equals(org)).orElseThrow(() -> ApiException.notFound("Template"));
        if (id != null && !Objects.equals(t.getVersion(), r.version())) {
            throw concurrent();
        }
        boolean duplicate = templates.findByOrganizationIdOrderByCode(org).stream()
                .anyMatch(x -> !x.getId().equals(id) && x.getCode().equals(r.code()) && x.getChannel().equals(r.channel()));
        if (duplicate) {
            throw ApiException.conflict("TEMPLATE_EXISTS", "A " + r.channel() + " template with code " + r.code() + " already exists.");
        }
        if ("SMS".equals(r.channel()) && r.body().length() > 480) {
            throw ApiException.badRequest("SMS_TOO_LONG", "SMS templates are limited to 480 characters (3 segments).");
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        if (id != null) {
            diff(changes, "subject", t.getSubject(), r.subject());
            diff(changes, "body", t.getBody(), r.body());
            diff(changes, "active", t.isActive(), r.active() == null ? t.isActive() : r.active());
        }
        t.setCode(r.code());
        t.setChannel(r.channel());
        t.setSubject(r.subject());
        t.setBody(r.body());
        if (r.active() != null) {
            t.setActive(r.active());
        }
        t.setUpdatedBy(TenantContext.actorId());
        t.setUpdatedAt(Instant.now());
        t = templates.saveAndFlush(t);
        audit.record(id == null ? "NOTIFICATION_TEMPLATE_CREATED" : "NOTIFICATION_TEMPLATE_UPDATED", "NOTIFICATION_TEMPLATE", t.getId(),
                null, AuditEvent.Outcome.SUCCESS, t.getCode() + " (" + t.getChannel() + ")", changes.isEmpty() ? null : changes);
        return toView(t, new HashMap<>());
    }

    public PreviewResponse preview(PreviewRequest r) {
        Map<String, String> values = new LinkedHashMap<>(KNOWN_PLACEHOLDERS);
        if (r.sample() != null) {
            r.sample().forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    values.put(k, v.length() > 200 ? v.substring(0, 200) : v);
                }
            });
        }
        Set<String> used = new LinkedHashSet<>(placeholders(r.subject()));
        used.addAll(placeholders(r.body()));
        List<String> unknown = used.stream().filter(p -> !KNOWN_PLACEHOLDERS.containsKey(p)).toList();
        String body = render(r.body(), values);
        Integer segments = "SMS".equals(r.channel()) ? Math.max(1, (int) Math.ceil(body.length() / 160.0)) : null;
        return new PreviewResponse(render(r.subject(), values), body, new ArrayList<>(used), unknown, KNOWN_PLACEHOLDERS, segments);
    }

    static List<String> placeholders(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            if (!out.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    static String render(String text, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "[" + m.group(1) + "?]" : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private TemplateView toView(NotificationTemplate t, Map<UUID, String> names) {
        String by = t.getUpdatedBy() == null ? null
                : names.computeIfAbsent(t.getUpdatedBy(), k -> users.findById(k).map(u -> u.getFullName()).orElse(null));
        List<String> ph = new ArrayList<>(placeholders(t.getSubject()));
        placeholders(t.getBody()).forEach(p -> {
            if (!ph.contains(p)) {
                ph.add(p);
            }
        });
        return new TemplateView(t.getId(), t.getCode(), t.getChannel(), t.getSubject(), t.getBody(), t.isActive(), ph, by,
                t.getUpdatedAt(), t.getVersion());
    }

    // ================================================================== escalation rules

    @Transactional(readOnly = true)
    public List<EscalationView> listEscalations() {
        return escalations.findByOrganizationIdOrderByEventTypeAscEscalationLevelAsc(TenantContext.requireOrganizationId())
                .stream().map(ConfigurationService::toView).toList();
    }

    @Transactional
    public EscalationView saveEscalation(UUID id, EscalationRequest r) {
        UUID org = TenantContext.requireOrganizationId();
        if (r.escalateToRole() == Role.SUPER_ADMIN || r.escalateToRole() == Role.PATIENT) {
            throw ApiException.rule("INVALID_ROLE", "Escalations can only target hospital staff roles.");
        }
        EscalationRule e = id == null ? new EscalationRule() : escalations.findById(id)
                .filter(x -> x.getOrganizationId().equals(org)).orElseThrow(() -> ApiException.notFound("Escalation rule"));
        if (id != null && !Objects.equals(e.getVersion(), r.version())) {
            throw concurrent();
        }
        boolean duplicate = escalations.findByOrganizationIdOrderByEventTypeAscEscalationLevelAsc(org).stream()
                .anyMatch(x -> !x.getId().equals(id) && x.getEventType() == r.eventType() && x.getEscalationLevel() == r.escalationLevel());
        if (duplicate) {
            throw ApiException.conflict("ESCALATION_LEVEL_EXISTS", "Level " + r.escalationLevel() + " already exists for " + r.eventType() + ".");
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        if (id != null) {
            diff(changes, "thresholdMinutes", e.getThresholdMinutes(), r.thresholdMinutes());
            diff(changes, "escalateToRole", e.getEscalateToRole(), r.escalateToRole());
            diff(changes, "priority", e.getPriority(), r.priority());
            diff(changes, "active", e.isActive(), r.active() == null ? e.isActive() : r.active());
        }
        e.setEventType(r.eventType());
        e.setThresholdMinutes(r.thresholdMinutes());
        e.setEscalateToRole(r.escalateToRole());
        e.setEscalationLevel(r.escalationLevel());
        e.setPriority(r.priority());
        e.setDescription(r.description().trim());
        if (r.active() != null) {
            e.setActive(r.active());
        }
        e.setUpdatedAt(Instant.now());
        e = escalations.saveAndFlush(e);
        audit.record(id == null ? "ESCALATION_RULE_CREATED" : "ESCALATION_RULE_UPDATED", "ESCALATION_RULE", e.getId(), null,
                AuditEvent.Outcome.SUCCESS, e.getEventType() + " level " + e.getEscalationLevel(), changes.isEmpty() ? null : changes);
        return toView(e);
    }

    private static EscalationView toView(EscalationRule e) {
        return new EscalationView(e.getId(), e.getEventType(), e.getThresholdMinutes(), e.getEscalateToRole(), e.getEscalationLevel(),
                e.getPriority(), e.getDescription(), e.isActive(), e.getUpdatedAt(), e.getVersion());
    }

    // ================================================================== observation thresholds

    @Transactional(readOnly = true)
    public List<ThresholdView> listThresholds() {
        return thresholds.findByOrganizationIdOrderByParameter(TenantContext.requireOrganizationId()).stream()
                .sorted((a, b) -> a.getParameter() == b.getParameter() ? a.getSeverity().compareTo(b.getSeverity())
                        : a.getParameter().compareTo(b.getParameter()))
                .map(ConfigurationService::toView).toList();
    }

    @Transactional
    public ThresholdView saveThreshold(UUID id, ThresholdRequest r) {
        UUID org = TenantContext.requireOrganizationId();
        if (r.minValue() == null && r.maxValue() == null) {
            throw ApiException.badRequest("BOUND_REQUIRED", "Set a lower bound, an upper bound, or both.");
        }
        if (r.minValue() != null && r.maxValue() != null && r.minValue().compareTo(r.maxValue()) >= 0) {
            throw ApiException.badRequest("INVALID_BOUNDS", "The lower bound must be below the upper bound.");
        }
        BigDecimal[] range = PARAMETER_RANGES.get(r.parameter());
        for (BigDecimal v : new BigDecimal[]{r.minValue(), r.maxValue()}) {
            if (v != null && (v.compareTo(range[0]) < 0 || v.compareTo(range[1]) > 0)) {
                throw ApiException.badRequest("OUT_OF_RANGE", r.parameter() + " thresholds must be between "
                        + range[0].toPlainString() + " and " + range[1].toPlainString() + ".");
            }
        }
        if (r.notifyRole() != null && r.notifyRole() != Role.DOCTOR && r.notifyRole() != Role.NURSE) {
            throw ApiException.rule("INVALID_ROLE", "Observation alerts can notify doctors or nurses only.");
        }
        ThresholdRule t = id == null ? new ThresholdRule() : thresholds.findById(id)
                .filter(x -> x.getOrganizationId().equals(org)).orElseThrow(() -> ApiException.notFound("Threshold rule"));
        if (id != null && !Objects.equals(t.getVersion(), r.version())) {
            throw concurrent();
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        if (id != null) {
            diff(changes, "minValue", plain(t.getMinValue()), plain(r.minValue()));
            diff(changes, "maxValue", plain(t.getMaxValue()), plain(r.maxValue()));
            diff(changes, "severity", t.getSeverity(), r.severity());
            diff(changes, "active", t.isActive(), r.active() == null ? t.isActive() : r.active());
        }
        t.setParameter(r.parameter());
        t.setMinValue(r.minValue());
        t.setMaxValue(r.maxValue());
        t.setSeverity(r.severity());
        t.setDescription(r.description().trim());
        t.setNotifyRole(r.notifyRole());
        if (r.active() != null) {
            t.setActive(r.active());
        }
        t = thresholds.saveAndFlush(t);
        audit.record(id == null ? "THRESHOLD_RULE_CREATED" : "THRESHOLD_RULE_UPDATED", "THRESHOLD_RULE", t.getId(), null,
                AuditEvent.Outcome.SUCCESS, t.describeBounds() + " (" + t.getSeverity() + ")", changes.isEmpty() ? null : changes);
        return toView(t);
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static ThresholdView toView(ThresholdRule t) {
        return new ThresholdView(t.getId(), t.getParameter(), t.getMinValue(), t.getMaxValue(), t.getSeverity(), t.getDescription(),
                t.getNotifyRole(), t.isActive(), t.describeBounds(), t.getUpdatedAt(), t.getVersion());
    }

    // ================================================================== discharge checklist template

    @Transactional(readOnly = true)
    public List<DischargeTemplateView> listDischargeTemplates() {
        return dischargeTemplates.findByOrganizationIdOrderByStepOrder(TenantContext.requireOrganizationId()).stream()
                .map(ConfigurationService::toView).toList();
    }

    @Transactional
    public DischargeTemplateView saveDischargeTemplate(UUID id, DischargeTemplateRequest r) {
        UUID org = TenantContext.requireOrganizationId();
        if (r.responsibleRole() == Role.SUPER_ADMIN || r.responsibleRole() == Role.PATIENT) {
            throw ApiException.rule("INVALID_ROLE", "Checklist steps must be owned by a hospital staff role.");
        }
        List<DischargeStepTemplate> all = dischargeTemplates.findByOrganizationIdOrderByStepOrder(org);
        DischargeStepTemplate t = id == null ? new DischargeStepTemplate() : all.stream().filter(x -> x.getId().equals(id))
                .findFirst().orElseThrow(() -> ApiException.notFound("Checklist step"));
        if (id != null && !Objects.equals(t.getVersion(), r.version())) {
            throw concurrent();
        }
        if (id != null && !t.getCode().equals(r.code())) {
            throw ApiException.rule("CODE_IMMUTABLE", "The step code cannot be changed; workflows reference it. Create a new step instead.");
        }
        if (id == null && all.stream().anyMatch(x -> x.getCode().equals(r.code()))) {
            throw ApiException.conflict("CODE_IN_USE", "Step code " + r.code() + " is already used.");
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        if (id != null) {
            diff(changes, "name", t.getName(), r.name());
            diff(changes, "responsibleRole", t.getResponsibleRole(), r.responsibleRole());
            diff(changes, "required", t.isRequired(), r.required());
            diff(changes, "active", t.isActive(), r.active() == null ? t.isActive() : r.active());
        }
        t.setCode(r.code());
        t.setName(r.name().trim());
        t.setDescription(HospitalSettingsService.blankToNull(r.description()));
        t.setCategory(r.category());
        t.setResponsibleRole(r.responsibleRole());
        t.setRequired(r.required());
        if (id == null) {
            t.setAutoCheck(HospitalSettingsService.blankToNull(r.autoCheck()));
            t.setStepOrder(all.stream().mapToInt(DischargeStepTemplate::getStepOrder).max().orElse(0) + 1);
        }
        if (r.active() != null) {
            t.setActive(r.active());
        }
        t = dischargeTemplates.saveAndFlush(t);
        audit.record(id == null ? "DISCHARGE_TEMPLATE_CREATED" : "DISCHARGE_TEMPLATE_UPDATED", "DISCHARGE_TEMPLATE", t.getId(), null,
                AuditEvent.Outcome.SUCCESS, t.getCode() + ": " + t.getName(), changes.isEmpty() ? null : changes);
        return toView(t);
    }

    @Transactional
    public List<DischargeTemplateView> reorderDischargeTemplates(List<UUID> orderedIds) {
        UUID org = TenantContext.requireOrganizationId();
        List<DischargeStepTemplate> all = dischargeTemplates.findByOrganizationIdOrderByStepOrder(org);
        Set<UUID> ids = new HashSet<>(orderedIds);
        if (ids.size() != orderedIds.size() || ids.size() != all.size()
                || !all.stream().allMatch(t -> ids.contains(t.getId()))) {
            throw ApiException.badRequest("INVALID_ORDER", "Provide every checklist step exactly once.");
        }
        Map<UUID, DischargeStepTemplate> byId = new HashMap<>();
        all.forEach(t -> byId.put(t.getId(), t));
        int order = 1;
        for (UUID id : orderedIds) {
            byId.get(id).setStepOrder(order++);
        }
        dischargeTemplates.saveAllAndFlush(all);
        audit.record("DISCHARGE_TEMPLATE_REORDERED", "DISCHARGE_TEMPLATE", null, null, AuditEvent.Outcome.SUCCESS,
                "Checklist order: " + String.join(" > ", orderedIds.stream().map(i -> byId.get(i).getCode()).toList()), null);
        return listDischargeTemplates();
    }

    private static DischargeTemplateView toView(DischargeStepTemplate t) {
        return new DischargeTemplateView(t.getId(), t.getCode(), t.getStepOrder(), t.getName(), t.getDescription(), t.getCategory(),
                t.getResponsibleRole(), t.isRequired(), t.getAutoCheck(), t.isActive(), t.getVersion());
    }

    // ================================================================== helpers

    private static void diff(Map<String, Object> changes, String field, Object old, Object now) {
        if (!Objects.equals(old, now)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("old", old == null ? null : String.valueOf(old));
            m.put("new", now == null ? null : String.valueOf(now));
            changes.put(field, m);
        }
    }

    private static ApiException concurrent() {
        return ApiException.conflict("CONCURRENT_UPDATE", "This record was changed by someone else. Reload and try again.");
    }
}
