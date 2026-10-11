package com.careflow.hms.clinical;

import com.careflow.hms.clinical.ClinicalDtos.AlertView;
import com.careflow.hms.clinical.ClinicalDtos.CompareView;
import com.careflow.hms.clinical.ClinicalDtos.EscalationRequest;
import com.careflow.hms.clinical.ClinicalDtos.ReasonRequest;
import com.careflow.hms.clinical.ClinicalDtos.ResolveAlertRequest;
import com.careflow.hms.clinical.ClinicalDtos.TrendsView;
import com.careflow.hms.clinical.ClinicalDtos.VitalView;
import com.careflow.hms.clinical.ClinicalDtos.VitalsRecorded;
import com.careflow.hms.clinical.ClinicalDtos.VitalsRequest;
import com.careflow.hms.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Tag(name = "Vital signs, recovery trends, clinical alerts and escalations")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ObservationController {

    private final VitalsService vitals;
    private final AlertService alerts;

    @Operation(summary = "Record vital signs; evaluates hospital threshold rules (decision support) and raises alerts")
    @PostMapping("/admissions/{id}/vitals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('VITALS_WRITE')")
    public VitalsRecorded record(@PathVariable UUID id, @Valid @RequestBody VitalsRequest body) {
        return vitals.record(id, body);
    }

    @GetMapping("/admissions/{id}/vitals")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public List<VitalView> list(@PathVariable UUID id, @RequestParam(required = false) Instant from,
                                @RequestParam(required = false) Instant to) {
        return vitals.list(id, from, to);
    }

    @PostMapping("/vitals/{id}/entered-in-error")
    @PreAuthorize("hasAuthority('VITALS_WRITE')")
    public VitalView markInError(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return vitals.markInError(id, body.reason());
    }

    @Operation(summary = "Recovery Trend Explorer: time series per parameter with threshold bands, crossings and data gaps")
    @GetMapping("/admissions/{id}/vitals/trends")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public TrendsView trends(@PathVariable UUID id, @RequestParam(required = false) Instant from,
                             @RequestParam(required = false) Instant to,
                             @RequestParam(required = false) Set<String> parameter) {
        return vitals.trends(id, from, to, parameter);
    }

    @Operation(summary = "Compare two time ranges (descriptive statistics only)")
    @GetMapping("/admissions/{id}/vitals/compare")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public CompareView compare(@PathVariable UUID id, @RequestParam Instant aFrom, @RequestParam Instant aTo,
                               @RequestParam Instant bFrom, @RequestParam Instant bTo) {
        return vitals.compare(id, aFrom, aTo, bFrom, bTo);
    }

    // ------------------------------------------------------------------ alerts

    @GetMapping("/admissions/{id}/alerts")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public List<AlertView> admissionAlerts(@PathVariable UUID id) {
        return alerts.forAdmission(id);
    }

    @Operation(summary = "Alerts across the caller's patients (unresolved and critical first)")
    @GetMapping("/alerts")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public PageResponse<AlertView> myAlerts(@RequestParam(required = false) Set<ClinicalAlert.Status> status,
                                            @RequestParam(required = false) ClinicalAlert.Severity severity,
                                            @RequestParam(required = false) ClinicalAlert.AlertType type,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return alerts.mine(status, severity, type, page, size);
    }

    @PostMapping("/alerts/{id}/acknowledge")
    @PreAuthorize("hasAuthority('ALERT_MANAGE')")
    public AlertView acknowledge(@PathVariable UUID id) {
        return alerts.acknowledge(id);
    }

    @PostMapping("/alerts/{id}/resolve")
    @PreAuthorize("hasAuthority('ALERT_MANAGE')")
    public AlertView resolve(@PathVariable UUID id, @Valid @RequestBody ResolveAlertRequest body) {
        return alerts.resolve(id, body.notes());
    }

    @Operation(summary = "Nurse-to-clinician escalation (ESCALATION alert assigned to the attending doctor + notification)")
    @PostMapping("/admissions/{id}/escalations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ALERT_MANAGE')")
    public AlertView escalate(@PathVariable UUID id, @Valid @RequestBody EscalationRequest body) {
        return alerts.escalate(id, body);
    }
}
