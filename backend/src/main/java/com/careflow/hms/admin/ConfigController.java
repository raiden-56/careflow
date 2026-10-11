package com.careflow.hms.admin;

import com.careflow.hms.admin.ConfigDtos.DischargeTemplateRequest;
import com.careflow.hms.admin.ConfigDtos.DischargeTemplateView;
import com.careflow.hms.admin.ConfigDtos.EscalationRequest;
import com.careflow.hms.admin.ConfigDtos.EscalationView;
import com.careflow.hms.admin.ConfigDtos.IntegrationRequest;
import com.careflow.hms.admin.ConfigDtos.IntegrationView;
import com.careflow.hms.admin.ConfigDtos.OrganizationProfile;
import com.careflow.hms.admin.ConfigDtos.OrganizationProfileRequest;
import com.careflow.hms.admin.ConfigDtos.PreviewRequest;
import com.careflow.hms.admin.ConfigDtos.PreviewResponse;
import com.careflow.hms.admin.ConfigDtos.ReorderRequest;
import com.careflow.hms.admin.ConfigDtos.SettingView;
import com.careflow.hms.admin.ConfigDtos.TariffRequest;
import com.careflow.hms.admin.ConfigDtos.TariffStatusRequest;
import com.careflow.hms.admin.ConfigDtos.TariffView;
import com.careflow.hms.admin.ConfigDtos.TemplateRequest;
import com.careflow.hms.admin.ConfigDtos.TemplateView;
import com.careflow.hms.admin.ConfigDtos.ThresholdRequest;
import com.careflow.hms.admin.ConfigDtos.ThresholdView;
import com.careflow.hms.admin.ConfigDtos.UpdateSettingsRequest;
import com.careflow.hms.billing.ServiceTariff;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.integration.IntegrationConfig;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Hospital configuration")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ConfigController {

    private static final String HOSPITAL_SETTINGS = "hasAuthority('SETTINGS_MANAGE') and !hasRole('SUPER_ADMIN')";

    private final HospitalSettingsService settings;
    private final ConfigurationService config;
    private final IntegrationService integrations;
    private final TariffService tariffs;

    // ------------------------------------------------------------------ settings

    @GetMapping("/settings")
    @PreAuthorize(HOSPITAL_SETTINGS)
    public List<SettingView> settings() {
        return settings.list();
    }

    @PutMapping("/settings")
    @PreAuthorize(HOSPITAL_SETTINGS)
    public List<SettingView> updateSettings(@Valid @RequestBody UpdateSettingsRequest body) {
        return settings.update(body.values());
    }

    @GetMapping("/settings/organization")
    @PreAuthorize(HOSPITAL_SETTINGS)
    public OrganizationProfile profile() {
        return settings.profile();
    }

    @PutMapping("/settings/organization")
    @PreAuthorize(HOSPITAL_SETTINGS)
    public OrganizationProfile updateProfile(@Valid @RequestBody OrganizationProfileRequest body) {
        return settings.updateProfile(body);
    }

    // ------------------------------------------------------------------ notification templates

    @GetMapping("/admin/notification-templates")
    @PreAuthorize(HOSPITAL_SETTINGS)
    public List<TemplateView> templates() {
        return config.listTemplates();
    }

    @PostMapping("/admin/notification-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(HOSPITAL_SETTINGS)
    public TemplateView createTemplate(@Valid @RequestBody TemplateRequest body) {
        return config.saveTemplate(null, body);
    }

    @PutMapping("/admin/notification-templates/{id}")
    @PreAuthorize(HOSPITAL_SETTINGS)
    public TemplateView updateTemplate(@PathVariable UUID id, @Valid @RequestBody TemplateRequest body) {
        return config.saveTemplate(id, body);
    }

    @PostMapping("/admin/notification-templates/preview")
    @PreAuthorize(HOSPITAL_SETTINGS)
    public PreviewResponse preview(@Valid @RequestBody PreviewRequest body) {
        return config.preview(body);
    }

    // ------------------------------------------------------------------ escalation rules

    @GetMapping("/admin/escalation-rules")
    @PreAuthorize(HOSPITAL_SETTINGS)
    public List<EscalationView> escalations() {
        return config.listEscalations();
    }

    @PostMapping("/admin/escalation-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(HOSPITAL_SETTINGS)
    public EscalationView createEscalation(@Valid @RequestBody EscalationRequest body) {
        return config.saveEscalation(null, body);
    }

    @PutMapping("/admin/escalation-rules/{id}")
    @PreAuthorize(HOSPITAL_SETTINGS)
    public EscalationView updateEscalation(@PathVariable UUID id, @Valid @RequestBody EscalationRequest body) {
        return config.saveEscalation(id, body);
    }

    // ------------------------------------------------------------------ observation thresholds

    @GetMapping("/admin/threshold-rules")
    @PreAuthorize(HOSPITAL_SETTINGS)
    public List<ThresholdView> thresholds() {
        return config.listThresholds();
    }

    @PostMapping("/admin/threshold-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(HOSPITAL_SETTINGS)
    public ThresholdView createThreshold(@Valid @RequestBody ThresholdRequest body) {
        return config.saveThreshold(null, body);
    }

    @PutMapping("/admin/threshold-rules/{id}")
    @PreAuthorize(HOSPITAL_SETTINGS)
    public ThresholdView updateThreshold(@PathVariable UUID id, @Valid @RequestBody ThresholdRequest body) {
        return config.saveThreshold(id, body);
    }

    // ------------------------------------------------------------------ discharge checklist

    @GetMapping("/admin/discharge-templates")
    @PreAuthorize(HOSPITAL_SETTINGS)
    public List<DischargeTemplateView> dischargeTemplates() {
        return config.listDischargeTemplates();
    }

    @PostMapping("/admin/discharge-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(HOSPITAL_SETTINGS)
    public DischargeTemplateView createDischargeTemplate(@Valid @RequestBody DischargeTemplateRequest body) {
        return config.saveDischargeTemplate(null, body);
    }

    @PutMapping("/admin/discharge-templates/{id}")
    @PreAuthorize(HOSPITAL_SETTINGS)
    public DischargeTemplateView updateDischargeTemplate(@PathVariable UUID id, @Valid @RequestBody DischargeTemplateRequest body) {
        return config.saveDischargeTemplate(id, body);
    }

    @PutMapping("/admin/discharge-templates/order")
    @PreAuthorize(HOSPITAL_SETTINGS)
    public List<DischargeTemplateView> reorder(@Valid @RequestBody ReorderRequest body) {
        return config.reorderDischargeTemplates(body.orderedIds());
    }

    // ------------------------------------------------------------------ integrations

    @GetMapping("/admin/integrations")
    @PreAuthorize("hasAuthority('INTEGRATION_MANAGE') and !hasRole('SUPER_ADMIN')")
    public List<IntegrationView> integrations() {
        return integrations.list();
    }

    @PutMapping("/admin/integrations/{type}")
    @PreAuthorize("hasAuthority('INTEGRATION_MANAGE') and !hasRole('SUPER_ADMIN')")
    public IntegrationView updateIntegration(@PathVariable IntegrationConfig.IntegrationType type,
                                             @Valid @RequestBody IntegrationRequest body) {
        return integrations.update(type, body);
    }

    // ------------------------------------------------------------------ tariffs

    @GetMapping("/admin/tariffs")
    @PreAuthorize("hasAuthority('TARIFF_MANAGE')")
    public PageResponse<TariffView> tariffs(@RequestParam(required = false) ServiceTariff.Category category,
                                            @RequestParam(defaultValue = "false") boolean includeInactive,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) String sort) {
        return tariffs.search(category, includeInactive, q, page, size, sort);
    }

    @PostMapping("/admin/tariffs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('TARIFF_MANAGE')")
    public TariffView createTariff(@Valid @RequestBody TariffRequest body) {
        return tariffs.create(body);
    }

    @PutMapping("/admin/tariffs/{id}")
    @PreAuthorize("hasAuthority('TARIFF_MANAGE')")
    public TariffView updateTariff(@PathVariable UUID id, @Valid @RequestBody TariffRequest body) {
        return tariffs.update(id, body);
    }

    @PostMapping("/admin/tariffs/{id}/deactivate")
    @PreAuthorize("hasAuthority('TARIFF_MANAGE')")
    public TariffView deactivateTariff(@PathVariable UUID id, @Valid @RequestBody(required = false) TariffStatusRequest body) {
        return tariffs.setActive(id, false, body == null ? null : body.reason());
    }

    @PostMapping("/admin/tariffs/{id}/activate")
    @PreAuthorize("hasAuthority('TARIFF_MANAGE')")
    public TariffView activateTariff(@PathVariable UUID id) {
        return tariffs.setActive(id, true, null);
    }
}
