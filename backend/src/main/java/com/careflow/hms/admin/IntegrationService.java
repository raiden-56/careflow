package com.careflow.hms.admin;

import com.careflow.hms.admin.ConfigDtos.IntegrationRequest;
import com.careflow.hms.admin.ConfigDtos.IntegrationView;
import com.careflow.hms.audit.AuditEvent;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.integration.IntegrationConfig;
import com.careflow.hms.integration.IntegrationConfig.IntegrationType;
import com.careflow.hms.integration.IntegrationConfig.Mode;
import com.careflow.hms.integration.IntegrationConfigRepository;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Integration boundary registry. Configuration only: no live connector is implemented in this build,
 * secrets are never stored (only the NAME of an environment / secret-manager entry), and switching a
 * boundary to LIVE requires explicit confirmation and is audited.
 */
@Service
@RequiredArgsConstructor
public class IntegrationService {

    public static final Map<IntegrationType, String> LABELS = Map.of(
            IntegrationType.ABDM, "ABDM (Ayushman Bharat Digital Mission)",
            IntegrationType.FHIR, "HL7 FHIR R4 exchange",
            IntegrationType.LIS, "Laboratory information system",
            IntegrationType.PAYMENT_GATEWAY, "Online payment gateway",
            IntegrationType.SMS, "SMS provider",
            IntegrationType.EMAIL, "Email provider",
            IntegrationType.INSURANCE_TPA, "Insurance / TPA claims");

    private final IntegrationConfigRepository repository;
    private final UserRepository users;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<IntegrationView> list() {
        UUID org = TenantContext.requireOrganizationId();
        Map<UUID, String> names = new HashMap<>();
        return repository.findByOrganizationIdOrderByIntegrationType(org).stream().map(c -> view(c, names)).toList();
    }

    @Transactional
    public IntegrationView update(IntegrationType type, IntegrationRequest r) {
        UUID org = TenantContext.requireOrganizationId();
        IntegrationConfig c = repository.findByOrganizationIdAndIntegrationType(org, type)
                .orElseThrow(() -> ApiException.notFound("Integration boundary"));
        if (!Objects.equals(c.getVersion(), r.version())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "This integration was changed by someone else. Reload and try again.");
        }
        String url = HospitalSettingsService.blankToNull(r.endpointUrl());
        if (url != null) {
            validateUrl(url, r.mode());
        }
        if ((r.mode() == Mode.SANDBOX || r.mode() == Mode.LIVE) && url == null) {
            throw ApiException.badRequest("ENDPOINT_REQUIRED", "Sandbox and live modes need the provider endpoint URL.");
        }
        if (r.mode() == Mode.LIVE && c.getMode() != Mode.LIVE && !r.confirmLive()) {
            throw ApiException.rule("LIVE_CONFIRMATION_REQUIRED",
                    "Switching to LIVE requires explicit confirmation. No live connector is implemented in this build.");
        }
        String notes = HospitalSettingsService.blankToNull(r.notes());
        if (notes != null && looksLikeSecret(notes)) {
            throw ApiException.badRequest("SECRET_NOT_ALLOWED", "Notes appear to contain a credential. Store secrets in the secret manager and reference them by name.");
        }
        Mode oldMode = c.getMode();
        Map<String, Object> changes = new LinkedHashMap<>();
        diff(changes, "mode", oldMode, r.mode());
        diff(changes, "endpointUrl", c.getEndpointUrl(), url);
        diff(changes, "secretName", c.getSettings().get("secretName"), HospitalSettingsService.blankToNull(r.secretName()));
        c.setMode(r.mode());
        c.setEndpointUrl(url);
        Map<String, Object> settings = new HashMap<>(c.getSettings());
        putOrRemove(settings, "secretName", HospitalSettingsService.blankToNull(r.secretName()));
        putOrRemove(settings, "adminNotes", notes);
        c.setSettings(settings);
        c.setUpdatedBy(TenantContext.actorId());
        c.setUpdatedAt(Instant.now());
        c = repository.saveAndFlush(c);
        String action = oldMode != r.mode() ? "INTEGRATION_MODE_CHANGED" : "INTEGRATION_UPDATED";
        String summary = type + (oldMode != r.mode() ? " mode " + oldMode + " -> " + r.mode() : " configuration updated")
                + (r.mode() == Mode.LIVE && oldMode != Mode.LIVE ? " (LIVE confirmed by administrator; no live connector implemented)" : "");
        audit.record(action, "INTEGRATION", c.getId(), null, AuditEvent.Outcome.SUCCESS, summary, changes.isEmpty() ? null : changes);
        return view(c, new HashMap<>());
    }

    static void validateUrl(String url, Mode mode) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_URL", "Enter a valid URL.");
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw ApiException.badRequest("INVALID_URL", "Enter an absolute URL including https://.");
        }
        if ((mode == Mode.SANDBOX || mode == Mode.LIVE) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw ApiException.badRequest("HTTPS_REQUIRED", "Sandbox and live endpoints must use https.");
        }
        if (uri.getUserInfo() != null || (uri.getQuery() != null && looksLikeSecret(uri.getQuery()))) {
            throw ApiException.badRequest("SECRET_NOT_ALLOWED", "Do not embed credentials in the endpoint URL.");
        }
    }

    static boolean looksLikeSecret(String text) {
        String t = text.toLowerCase();
        return t.matches("(?s).*(password|passwd|secret|api[_-]?key|token|bearer)\\s*[=:]\\s*\\S+.*");
    }

    private static void putOrRemove(Map<String, Object> m, String key, Object value) {
        if (value == null) {
            m.remove(key);
        } else {
            m.put(key, value);
        }
    }

    private static void diff(Map<String, Object> changes, String field, Object old, Object now) {
        if (!Objects.equals(old, now)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("old", old == null ? null : String.valueOf(old));
            m.put("new", now == null ? null : String.valueOf(now));
            changes.put(field, m);
        }
    }

    /** Describes what actually happens for each mode in this build (nothing is ever sent to a live provider). */
    public static String adapterStatus(IntegrationType type, Mode mode) {
        return switch (mode) {
            case DISABLED -> "Disabled: no data leaves CareFlow for this boundary.";
            case DEV_ADAPTER -> switch (type) {
                case SMS, EMAIL -> "Development adapter: messages are written to the dev outbox and never sent.";
                case LIS -> "Development adapter: results are entered manually by staff.";
                default -> "Development adapter: no external system is contacted.";
            };
            case SANDBOX -> "Sandbox configured: endpoint recorded for testing; no connector is implemented, so no traffic is sent.";
            case LIVE -> "Marked LIVE by an administrator, but no live connector is implemented in this build; no traffic is sent.";
        };
    }

    IntegrationView view(IntegrationConfig c, Map<UUID, String> names) {
        String by = c.getUpdatedBy() == null ? null
                : names.computeIfAbsent(c.getUpdatedBy(), k -> users.findById(k).map(u -> u.getFullName()).orElse(null));
        Object secret = c.getSettings().get("secretName");
        Object note = c.getSettings().get("note");
        Object adminNotes = c.getSettings().get("adminNotes");
        return new IntegrationView(c.getId(), c.getIntegrationType(), LABELS.get(c.getIntegrationType()), c.getMode(),
                c.getEndpointUrl(), secret == null ? null : String.valueOf(secret), adminNotes == null ? null : String.valueOf(adminNotes),
                note == null ? null : String.valueOf(note), adapterStatus(c.getIntegrationType(), c.getMode()), false, by,
                c.getUpdatedAt(), c.getVersion());
    }
}
