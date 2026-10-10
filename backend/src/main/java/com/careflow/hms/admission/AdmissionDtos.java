package com.careflow.hms.admission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AdmissionDtos {

    private AdmissionDtos() {
    }

    public record AdmitRequest(
            @NotNull UUID patientId,
            @NotNull Admission.AdmissionType admissionType,
            Instant admittedAt,
            @NotBlank @Size(max = 2000) String admissionReason,
            @NotNull UUID departmentId,
            @NotNull UUID attendingDoctorId,
            @Size(max = 200) String referringDoctor,
            LocalDate expectedDischargeDate,
            boolean requiresIsolation,
            @Size(max = 500) String bedRequirements,
            /** Optional: assign a bed immediately. Without it the admission waits in PENDING_BED. */
            UUID bedId) {
    }

    public record UpdateAdmissionRequest(
            @NotBlank @Size(max = 2000) String admissionReason,
            @NotNull UUID departmentId,
            @NotNull UUID attendingDoctorId,
            @Size(max = 200) String referringDoctor,
            LocalDate expectedDischargeDate,
            boolean requiresIsolation,
            @Size(max = 500) String bedRequirements,
            @NotNull Long version) {
    }

    public record ExpectedDischargeRequest(LocalDate expectedDischargeDate) {
    }

    public record AssignBedRequest(@NotNull UUID bedId, @Size(max = 500) String notes) {
    }

    public record CancelRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record CareTeamRequest(@NotNull UUID userId, @NotNull AdmissionCareTeam.CareRole careRole) {
    }

    public record PatientBrief(UUID id, String mrn, String fullName, int age, String gender, String bloodGroup) {
    }

    public record Location(UUID bedId, String bedNumber, UUID roomId, String roomNumber, UUID wardId, String wardName,
                           Instant since) {
    }

    public record AdmissionSummary(UUID id, String admissionNumber, Admission.Status status,
                                   Admission.AdmissionType admissionType, Instant admittedAt,
                                   LocalDate expectedDischargeDate, Instant dischargedAt, PatientBrief patient,
                                   UUID departmentId, String departmentName, UUID attendingDoctorId,
                                   String attendingDoctorName, Location location, long lengthOfStayDays,
                                   boolean requiresIsolation, List<String> allergies) {
    }

    public record CareTeamMember(UUID id, UUID userId, String fullName, String role, AdmissionCareTeam.CareRole careRole,
                                 Instant addedAt) {
    }

    public record BedStay(UUID bedId, String bedNumber, String roomNumber, String wardName, Instant assignedAt,
                          Instant releasedAt, String releaseReason) {
    }

    public record TransferView(UUID id, UUID admissionId, String admissionNumber, String patientName, String patientMrn,
                               Transfer.Status status, Transfer.Priority priority, String reason,
                               String fromLocation, UUID toWardId, String toWardName, UUID toBedId, String toLocation,
                               String requestedBy, Instant requestedAt, String decidedBy, Instant decidedAt,
                               String decisionNotes, String completedBy, Instant completedAt, Long version) {
    }

    public record AdmissionDetail(AdmissionSummary summary, String admissionReason, String referringDoctor,
                                  String bedRequirements, String cancellationReason,
                                  Admission.Disposition dischargeDisposition, String createdByName, Instant createdAt,
                                  List<CareTeamMember> careTeam, List<BedStay> bedHistory,
                                  List<TransferView> transfers, boolean clinicalAccess, Long version) {
    }

    public record TransferRequest(UUID toWardId, UUID toBedId, @NotBlank @Size(max = 1000) String reason,
                                  Transfer.Priority priority) {
    }

    public record TransferDecisionRequest(UUID toBedId, @Size(max = 1000) String notes) {
    }
}
