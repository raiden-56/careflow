package com.careflow.hms.consent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class PrivacyDtos {

    private PrivacyDtos() {
    }

    public record ConsentView(UUID id, UUID patientId, String patientName, String patientMrn, UUID admissionId,
                              String admissionNumber, ConsentRecord.ConsentType consentType, String purpose, String sharedWith,
                              ConsentRecord.Status status, ConsentRecord.Status effectiveStatus, Instant grantedAt,
                              Instant expiresAt, Instant withdrawnAt, String withdrawalReason, String capturedByName,
                              ConsentRecord.CaptureMethod captureMethod, String notes, boolean portalWithdrawable,
                              Long version) {
    }

    public record ConsentRequest(
            @NotNull UUID patientId,
            UUID admissionId,
            @NotNull ConsentRecord.ConsentType consentType,
            @NotBlank @Size(max = 500) String purpose,
            @Size(max = 200) String sharedWith,
            @NotNull ConsentRecord.CaptureMethod captureMethod,
            @Pattern(regexp = "^(GRANTED|DECLINED)$", message = "must be GRANTED or DECLINED") String decision,
            Instant grantedAt,
            Instant expiresAt,
            @Size(max = 1000) String notes) {
    }

    public record WithdrawRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    public record RequestView(UUID id, UUID patientId, String patientName, String patientMrn,
                              PatientRequest.RequestType requestType, String subject, String description,
                              PatientRequest.Status status, String raisedByName, Instant raisedAt, String response,
                              String respondedByName, Instant respondedAt, Long version) {
    }

    public record NewRequest(
            @NotNull PatientRequest.RequestType requestType,
            @NotBlank @Size(min = 3, max = 300) String subject,
            @NotBlank @Size(min = 10, max = 2000) String description) {
    }

    public record RespondRequest(
            @NotNull PatientRequest.Status status,
            @NotBlank @Size(min = 3, max = 2000) String response,
            @NotNull Long version) {
    }

    /** Patient-facing access-log entry: who, in which role, what (plain language) and when. No internal details. */
    public record AccessEntry(Instant at, String staffName, String role, String activity) {
    }

    public record PrivacyStats(long consentsGranted, long consentsWithdrawn, long requestsOpen, long requestsInReview,
                               long requestsClosedLast30Days) {
    }
}
