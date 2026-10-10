package com.careflow.hms.patient;

import com.careflow.hms.common.PageResponse;
import com.careflow.hms.patient.PatientDtos.CaregiverGrantRequest;
import com.careflow.hms.patient.PatientDtos.CaregiverGrantView;
import com.careflow.hms.patient.PatientDtos.PatientDetail;
import com.careflow.hms.patient.PatientDtos.PatientRequest;
import com.careflow.hms.patient.PatientDtos.PatientSummary;
import com.careflow.hms.patient.PatientDtos.PortalAccountRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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

@Tag(name = "Patients")
@RestController
@RequestMapping("/api/patients")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService service;

    @GetMapping
    @PreAuthorize("hasAuthority('PATIENT_DEMOGRAPHICS_READ')")
    public PageResponse<PatientSummary> search(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) Patient.Status status,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(required = false) String sort) {
        return service.search(q, status, page, size, sort);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PATIENT_REGISTER')")
    public PatientDetail register(@Valid @RequestBody PatientRequest body) {
        return service.register(body);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PATIENT_DEMOGRAPHICS_READ','PORTAL_ACCESS')")
    public PatientDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PATIENT_REGISTER')")
    public PatientDetail update(@PathVariable UUID id, @Valid @RequestBody PatientRequest body) {
        return service.update(id, body);
    }

    @PostMapping("/{id}/portal-account")
    @PreAuthorize("hasAuthority('PATIENT_REGISTER')")
    public PatientDetail portalAccount(@PathVariable UUID id, @Valid @RequestBody PortalAccountRequest body) {
        return service.createPortalAccount(id, body.email());
    }

    @GetMapping("/{id}/caregivers")
    @PreAuthorize("hasAnyAuthority('PATIENT_REGISTER','PORTAL_ACCESS')")
    public List<CaregiverGrantView> caregivers(@PathVariable UUID id) {
        return service.listCaregivers(id);
    }

    @PostMapping("/{id}/caregivers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('PATIENT_REGISTER','PORTAL_ACCESS')")
    public CaregiverGrantView addCaregiver(@PathVariable UUID id, @Valid @RequestBody CaregiverGrantRequest body) {
        return service.addCaregiver(id, body);
    }

    @DeleteMapping("/{id}/caregivers/{grantId}")
    @PreAuthorize("hasAnyAuthority('PATIENT_REGISTER','PORTAL_ACCESS')")
    public CaregiverGrantView revokeCaregiver(@PathVariable UUID id, @PathVariable UUID grantId) {
        return service.revokeCaregiver(id, grantId);
    }
}
