package com.careflow.hms.incident;

import com.careflow.hms.common.PageResponse;
import com.careflow.hms.incident.IncidentDtos.AssignRequest;
import com.careflow.hms.incident.IncidentDtos.HistoryEntry;
import com.careflow.hms.incident.IncidentDtos.IncidentDetail;
import com.careflow.hms.incident.IncidentDtos.IncidentStats;
import com.careflow.hms.incident.IncidentDtos.IncidentSummary;
import com.careflow.hms.incident.IncidentDtos.MedicationEventOption;
import com.careflow.hms.incident.IncidentDtos.PatientOption;
import com.careflow.hms.incident.IncidentDtos.ReportRequest;
import com.careflow.hms.incident.IncidentDtos.ReviewerOption;
import com.careflow.hms.incident.IncidentDtos.TransitionRequest;
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

@Tag(name = "Safety incidents")
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private static final String REPORTER_OR_REVIEWER = "hasAnyAuthority('INCIDENT_REPORT','INCIDENT_REVIEW') and !hasRole('SUPER_ADMIN') and !hasRole('PATIENT')";

    private final IncidentService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('INCIDENT_REPORT') and !hasRole('SUPER_ADMIN') and !hasRole('PATIENT')")
    public IncidentDetail report(@Valid @RequestBody ReportRequest body) {
        return service.report(body);
    }

    @GetMapping("/mine")
    @PreAuthorize(REPORTER_OR_REVIEWER)
    public PageResponse<IncidentSummary> mine(@RequestParam(required = false) IncidentReport.Status status,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(required = false) String sort) {
        return service.mine(status, page, size, sort);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('INCIDENT_REVIEW')")
    public PageResponse<IncidentSummary> all(@RequestParam(required = false) IncidentReport.Status status,
                                             @RequestParam(required = false) IncidentReport.Severity severity,
                                             @RequestParam(required = false) IncidentReport.IncidentType type,
                                             @RequestParam(required = false) UUID reviewerId,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @RequestParam(required = false) String sort) {
        return service.all(status, severity, type, reviewerId, q, page, size, sort);
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('INCIDENT_REVIEW')")
    public IncidentStats stats() {
        return service.stats();
    }

    @GetMapping("/reviewers")
    @PreAuthorize("hasAuthority('INCIDENT_REVIEW')")
    public List<ReviewerOption> reviewers() {
        return service.reviewers();
    }

    @GetMapping("/patient-lookup")
    @PreAuthorize("hasAuthority('INCIDENT_REPORT')")
    public List<PatientOption> patientLookup(@RequestParam String q) {
        return service.patientLookup(q);
    }

    @GetMapping("/medication-events")
    @PreAuthorize("hasAuthority('INCIDENT_REPORT')")
    public List<MedicationEventOption> medicationEvents(@RequestParam UUID admissionId) {
        return service.medicationEvents(admissionId);
    }

    @GetMapping("/{id}")
    @PreAuthorize(REPORTER_OR_REVIEWER)
    public IncidentDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/history")
    @PreAuthorize(REPORTER_OR_REVIEWER)
    public List<HistoryEntry> history(@PathVariable UUID id) {
        return service.history(id);
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAuthority('INCIDENT_REVIEW')")
    public IncidentDetail assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest body) {
        return service.assign(id, body.reviewerId());
    }

    @PostMapping("/{id}/transition")
    @PreAuthorize("hasAuthority('INCIDENT_REVIEW')")
    public IncidentDetail transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest body) {
        return service.transition(id, body);
    }
}
