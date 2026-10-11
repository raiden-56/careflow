package com.careflow.hms.consent;

import com.careflow.hms.common.PageResponse;
import com.careflow.hms.consent.PrivacyDtos.AccessEntry;
import com.careflow.hms.consent.PrivacyDtos.ConsentRequest;
import com.careflow.hms.consent.PrivacyDtos.ConsentView;
import com.careflow.hms.consent.PrivacyDtos.NewRequest;
import com.careflow.hms.consent.PrivacyDtos.PrivacyStats;
import com.careflow.hms.consent.PrivacyDtos.RequestView;
import com.careflow.hms.consent.PrivacyDtos.RespondRequest;
import com.careflow.hms.consent.PrivacyDtos.WithdrawRequest;
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

import java.util.List;
import java.util.UUID;

@Tag(name = "Consent, patient requests and access transparency")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PrivacyController {

    private final PrivacyService service;

    // ------------------------------------------------------------------ staff

    @GetMapping("/consents")
    @PreAuthorize("hasAuthority('CONSENT_MANAGE') and hasAuthority('PATIENT_DEMOGRAPHICS_READ')")
    public PageResponse<ConsentView> consents(@RequestParam(required = false) String patientMrn,
                                              @RequestParam(required = false) ConsentRecord.Status status,
                                              @RequestParam(required = false) ConsentRecord.ConsentType type,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(required = false) String sort) {
        return service.searchConsents(patientMrn, status, type, page, size, sort);
    }

    @PostMapping("/consents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CONSENT_MANAGE')")
    public ConsentView record(@Valid @RequestBody ConsentRequest body) {
        return service.recordConsent(body);
    }

    @PostMapping("/consents/{id}/withdraw")
    @PreAuthorize("hasAuthority('CONSENT_MANAGE')")
    public ConsentView withdraw(@PathVariable UUID id, @Valid @RequestBody WithdrawRequest body) {
        return service.withdrawByStaff(id, body.reason());
    }

    @GetMapping("/patient-requests")
    @PreAuthorize("hasAuthority('PATIENT_REQUEST_MANAGE')")
    public PageResponse<RequestView> requests(@RequestParam(required = false) PatientRequest.Status status,
                                              @RequestParam(required = false) PatientRequest.RequestType type,
                                              @RequestParam(required = false) String patientMrn,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(required = false) String sort) {
        return service.searchRequests(status, type, patientMrn, page, size, sort);
    }

    @PostMapping("/patient-requests/{id}/respond")
    @PreAuthorize("hasAuthority('PATIENT_REQUEST_MANAGE')")
    public RequestView respond(@PathVariable UUID id, @Valid @RequestBody RespondRequest body) {
        return service.respond(id, body);
    }

    @GetMapping("/privacy/stats")
    @PreAuthorize("hasAnyAuthority('PATIENT_REQUEST_MANAGE','CONSENT_MANAGE') and !hasRole('SUPER_ADMIN')")
    public PrivacyStats stats() {
        return service.stats();
    }

    // ------------------------------------------------------------------ patient portal

    @GetMapping("/portal/privacy/{patientId}/consents")
    @PreAuthorize("hasAuthority('PORTAL_ACCESS')")
    public List<ConsentView> portalConsents(@PathVariable UUID patientId) {
        return service.portalConsents(patientId);
    }

    @PostMapping("/portal/privacy/{patientId}/consents/{consentId}/withdraw")
    @PreAuthorize("hasAuthority('PORTAL_ACCESS')")
    public ConsentView portalWithdraw(@PathVariable UUID patientId, @PathVariable UUID consentId,
                                      @Valid @RequestBody WithdrawRequest body) {
        return service.portalWithdraw(patientId, consentId, body.reason());
    }

    @GetMapping("/portal/privacy/{patientId}/requests")
    @PreAuthorize("hasAuthority('PORTAL_ACCESS')")
    public List<RequestView> portalRequests(@PathVariable UUID patientId) {
        return service.portalRequests(patientId);
    }

    @PostMapping("/portal/privacy/{patientId}/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PORTAL_ACCESS')")
    public RequestView portalSubmit(@PathVariable UUID patientId, @Valid @RequestBody NewRequest body) {
        return service.portalSubmit(patientId, body);
    }

    @GetMapping("/portal/privacy/{patientId}/access-log")
    @PreAuthorize("hasAuthority('PORTAL_ACCESS')")
    public PageResponse<AccessEntry> accessLog(@PathVariable UUID patientId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        return service.accessLog(patientId, page, size);
    }
}
