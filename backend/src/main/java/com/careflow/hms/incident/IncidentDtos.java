package com.careflow.hms.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class IncidentDtos {

    private IncidentDtos() {
    }

    public record ReportRequest(
            @NotNull IncidentReport.IncidentType incidentType,
            @NotNull IncidentReport.Severity severity,
            @NotNull Instant occurredAt,
            @Size(max = 200) String location,
            UUID wardId,
            UUID patientId,
            UUID admissionId,
            UUID medicationAdministrationId,
            @NotBlank @Size(min = 10, max = 5000) String description,
            @Size(max = 3000) String immediateActions) {
    }

    public record AssignRequest(@NotNull UUID reviewerId) {
    }

    public record TransitionRequest(
            @NotNull IncidentReport.Status status,
            @Size(max = 5000) String reviewNotes,
            @Size(max = 5000) String correctiveActions,
            @NotNull Long version) {
    }

    public record IncidentSummary(UUID id, String incidentNumber, IncidentReport.IncidentType incidentType,
                                  IncidentReport.Severity severity, IncidentReport.Status status, Instant occurredAt,
                                  Instant reportedAt, String location, String wardName, String patientName, String patientMrn,
                                  boolean patientLinked, String reportedByName, String reviewerName, boolean mine) {
    }

    public record IncidentDetail(UUID id, String incidentNumber, IncidentReport.IncidentType incidentType,
                                 IncidentReport.Severity severity, IncidentReport.Status status, Instant occurredAt,
                                 Instant reportedAt, String location, UUID wardId, String wardName, UUID patientId,
                                 String patientName, String patientMrn, UUID admissionId, String admissionNumber,
                                 UUID medicationAdministrationId, String medicationEvent, String description,
                                 String immediateActions, UUID reportedById, String reportedByName, String reportedByRole,
                                 UUID reviewerId, String reviewerName, String reviewNotes, String correctiveActions,
                                 Instant closedAt, boolean canReview, List<IncidentReport.Status> allowedTransitions,
                                 Long version) {
    }

    public record HistoryEntry(Instant at, String actorName, String actorRole, String action, String summary,
                               Map<String, Object> details) {
    }

    public record IncidentStats(long reported, long underReview, long actionRequired, long closed,
                                long severeOpen, long reportedLast30Days) {
    }

    public record PatientOption(UUID patientId, String patientName, String mrn, UUID admissionId, String admissionNumber,
                                String location) {
    }

    public record MedicationEventOption(UUID id, Instant scheduledAt, String medication, String status, String dose) {
    }

    public record ReviewerOption(UUID id, String fullName, String role) {
    }
}
