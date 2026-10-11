package com.careflow.hms.discharge;

import com.careflow.hms.audit.AuditService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.discharge.DischargeDtos.TemplateRequest;
import com.careflow.hms.discharge.DischargeDtos.TemplateView;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Hospital-configurable discharge checklist. Changes apply to workflows started afterwards; running workflows
 * keep the snapshot taken at initiation.
 */
@Service
@RequiredArgsConstructor
public class DischargeTemplateService {

    private final DischargeStepTemplateRepository templates;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<TemplateView> list() {
        return templates.findByOrganizationIdOrderByStepOrder(TenantContext.requireOrganizationId()).stream().map(this::view).toList();
    }

    @Transactional
    public TemplateView create(TemplateRequest r) {
        UUID org = TenantContext.requireOrganizationId();
        String code = r.code() == null ? null : r.code().trim().toUpperCase(Locale.ROOT);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,39}")) {
            throw ApiException.badRequest("INVALID_CODE", "Use an upper-case code of 3-40 letters, digits or underscores.");
        }
        if (templates.existsByOrganizationIdAndCode(org, code)) {
            throw ApiException.conflict("CODE_EXISTS", "A step with code " + code + " already exists.");
        }
        DischargeStepTemplate t = new DischargeStepTemplate();
        t.setCode(code);
        apply(t, r);
        t = templates.saveAndFlush(t);
        audit.record("DISCHARGE_TEMPLATE_CREATED", "DISCHARGE_STEP_TEMPLATE", t.getId(), null, code + ": " + t.getName());
        return view(t);
    }

    @Transactional
    public TemplateView update(UUID id, TemplateRequest r) {
        DischargeStepTemplate t = templates.findByIdAndOrganizationId(id, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Discharge step template"));
        if (r.version() != null && !Objects.equals(r.version(), t.getVersion())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "This step was changed by someone else. Reload and try again.");
        }
        apply(t, r);
        audit.record("DISCHARGE_TEMPLATE_UPDATED", "DISCHARGE_STEP_TEMPLATE", t.getId(), null,
                t.getCode() + ": " + t.getName() + (t.isActive() ? "" : " (inactive)"));
        return view(templates.saveAndFlush(t));
    }

    private void apply(DischargeStepTemplate t, TemplateRequest r) {
        if (r.responsibleRole() == Role.PATIENT || r.responsibleRole() == Role.SUPER_ADMIN) {
            throw ApiException.badRequest("INVALID_ROLE", "Steps are owned by a hospital staff role.");
        }
        String auto = r.autoCheck() == null || r.autoCheck().isBlank() ? null : r.autoCheck().trim().toUpperCase(Locale.ROOT);
        if (auto != null && !DischargeChecks.KNOWN.contains(auto)) {
            throw ApiException.badRequest("UNKNOWN_AUTO_CHECK", "Unknown automatic check " + auto + ".");
        }
        if (r.stepOrder() < 1 || r.stepOrder() > 999) {
            throw ApiException.badRequest("INVALID_ORDER", "Step order must be between 1 and 999.");
        }
        t.setName(r.name().trim());
        t.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().trim());
        t.setCategory(r.category());
        t.setResponsibleRole(r.responsibleRole());
        t.setRequired(r.required());
        t.setAutoCheck(auto);
        t.setActive(r.active());
        t.setStepOrder(r.stepOrder());
    }

    private TemplateView view(DischargeStepTemplate t) {
        return new TemplateView(t.getId(), t.getCode(), t.getStepOrder(), t.getName(), t.getDescription(), t.getCategory(),
                t.getResponsibleRole(), t.isRequired(), t.getAutoCheck(), t.isActive(), t.getVersion());
    }
}
