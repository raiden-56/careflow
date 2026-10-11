package com.careflow.hms.admin;

import com.careflow.hms.billing.ServiceTariff;
import com.careflow.hms.clinical.ThresholdRule;
import com.careflow.hms.discharge.DischargeStepTemplate;
import com.careflow.hms.integration.IntegrationConfig;
import com.careflow.hms.notification.EscalationRule;
import com.careflow.hms.security.Role;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ConfigDtos {

    private ConfigDtos() {
    }

    // ------------------------------------------------------------------ hospital settings

    public enum SettingType { INTEGER, BOOLEAN, ENUM, MULTI_ENUM, TEXT }

    public record SettingView(String key, String group, String label, String value, String defaultValue,
                              String description, SettingType type, Integer min, Integer max, List<String> options,
                              boolean overridden, Instant updatedAt, String updatedBy) {
    }

    public record UpdateSettingsRequest(@NotNull @NotEmpty Map<String, String> values) {
    }

    public record OrganizationProfile(UUID id, String code, String name, String legalName, String timezone, String currency,
                                      String address, String city, String state, String phone, String email,
                                      String registrationNumber, Long version) {
    }

    public record OrganizationProfileRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String legalName,
            @Size(max = 500) String address,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Pattern(regexp = "^[0-9+\\- ]{7,20}$", message = "must be a valid phone number") String phone,
            @Email @Size(max = 200) String email,
            @Size(max = 100) String registrationNumber,
            @NotNull Long version) {
    }

    // ------------------------------------------------------------------ notification templates

    public record TemplateView(UUID id, String code, String channel, String subject, String body, boolean active,
                               List<String> placeholders, String updatedBy, Instant updatedAt, Long version) {
    }

    public record TemplateRequest(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,63}$", message = "use upper-case letters, digits and _ (3-64)") String code,
            @NotBlank @Pattern(regexp = "^(IN_APP|EMAIL|SMS)$", message = "must be IN_APP, EMAIL or SMS") String channel,
            @NotBlank @Size(max = 300) String subject,
            @NotBlank @Size(max = 4000) String body,
            Boolean active,
            Long version) {
    }

    public record PreviewRequest(@NotBlank @Size(max = 300) String subject, @NotBlank @Size(max = 4000) String body,
                                 @Pattern(regexp = "^(IN_APP|EMAIL|SMS)$") String channel, Map<String, String> sample) {
    }

    public record PreviewResponse(String subject, String body, List<String> placeholders, List<String> unknownPlaceholders,
                                  Map<String, String> knownPlaceholders, Integer smsSegments) {
    }

    // ------------------------------------------------------------------ escalation rules

    public record EscalationView(UUID id, EscalationRule.EventType eventType, int thresholdMinutes, Role escalateToRole,
                                 int escalationLevel, String priority, String description, boolean active,
                                 Instant updatedAt, Long version) {
    }

    public record EscalationRequest(
            @NotNull EscalationRule.EventType eventType,
            @NotNull @Min(0) @Max(10080) Integer thresholdMinutes,
            @NotNull Role escalateToRole,
            @NotNull @Min(1) @Max(5) Integer escalationLevel,
            @NotBlank @Pattern(regexp = "^(LOW|NORMAL|HIGH|CRITICAL)$", message = "must be LOW, NORMAL, HIGH or CRITICAL") String priority,
            @NotBlank @Size(max = 500) String description,
            Boolean active,
            Long version) {
    }

    // ------------------------------------------------------------------ observation thresholds

    public record ThresholdView(UUID id, ThresholdRule.Parameter parameter, BigDecimal minValue, BigDecimal maxValue,
                                ThresholdRule.Severity severity, String description, Role notifyRole, boolean active,
                                String bounds, Instant updatedAt, Long version) {
    }

    public record ThresholdRequest(
            @NotNull ThresholdRule.Parameter parameter,
            @Digits(integer = 6, fraction = 2) BigDecimal minValue,
            @Digits(integer = 6, fraction = 2) BigDecimal maxValue,
            @NotNull ThresholdRule.Severity severity,
            @NotBlank @Size(max = 300) String description,
            Role notifyRole,
            Boolean active,
            Long version) {
    }

    // ------------------------------------------------------------------ discharge checklist

    public record DischargeTemplateView(UUID id, String code, int stepOrder, String name, String description,
                                        DischargeStepTemplate.Category category, Role responsibleRole, boolean required,
                                        String autoCheck, boolean active, Long version) {
    }

    public record DischargeTemplateRequest(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,39}$", message = "use upper-case letters, digits and _ (3-40)") String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            @NotNull DischargeStepTemplate.Category category,
            @NotNull Role responsibleRole,
            @NotNull Boolean required,
            @Size(max = 40) String autoCheck,
            Boolean active,
            Long version) {
    }

    public record ReorderRequest(@NotEmpty List<UUID> orderedIds) {
    }

    // ------------------------------------------------------------------ integrations

    public record IntegrationView(UUID id, IntegrationConfig.IntegrationType integrationType, String label,
                                  IntegrationConfig.Mode mode, String endpointUrl, String secretName, String notes,
                                  String boundaryNote, String adapterStatus, boolean liveImplemented,
                                  String updatedBy, Instant updatedAt, Long version) {
    }

    public record IntegrationRequest(
            @NotNull IntegrationConfig.Mode mode,
            @Size(max = 500) String endpointUrl,
            @Pattern(regexp = "^$|^[A-Z][A-Z0-9_]{2,63}$", message = "must be an environment/secret-manager name such as ABDM_CLIENT_SECRET (never the secret itself)") String secretName,
            @Size(max = 500) String notes,
            boolean confirmLive,
            @NotNull Long version) {
    }

    // ------------------------------------------------------------------ tariffs

    public record TariffView(UUID id, String code, String name, ServiceTariff.Category category, BigDecimal unitPrice,
                             BigDecimal taxRate, ServiceTariff.BillingUnit billingUnit, String roomType, UUID departmentId,
                             String departmentName, String description, boolean active, LocalDate effectiveFrom,
                             Instant updatedAt, Long version) {
    }

    public record TariffRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{2,32}$", message = "use 2-32 upper-case letters, digits, - or _") String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull ServiceTariff.Category category,
            @NotNull @DecimalMin(value = "0.00", message = "must be zero or more") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
            @NotNull @DecimalMin(value = "0.00", message = "must be between 0 and 100") @DecimalMax(value = "100.00", message = "must be between 0 and 100")
            @Digits(integer = 3, fraction = 2) BigDecimal taxRate,
            @NotNull ServiceTariff.BillingUnit billingUnit,
            @Pattern(regexp = "^$|^(GENERAL|SEMI_PRIVATE|PRIVATE|DELUXE|ICU|ISOLATION)$", message = "must be a valid room type") String roomType,
            UUID departmentId,
            @Size(max = 500) String description,
            LocalDate effectiveFrom,
            Long version) {
    }

    public record TariffStatusRequest(@Size(max = 500) String reason) {
    }
}
