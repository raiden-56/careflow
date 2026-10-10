package com.careflow.hms.patient;

import com.careflow.hms.admission.Admission;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class PatientDtos {

    private PatientDtos() {
    }

    public record PatientRequest(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotNull @Past LocalDate dateOfBirth,
            @NotNull Patient.Gender gender,
            @Pattern(regexp = "^(A|B|AB|O)[+-]$", message = "must be a valid blood group, e.g. O+") String bloodGroup,
            @Pattern(regexp = "^[0-9+\\- ]{7,20}$", message = "must be a valid phone number") String phone,
            @Email @Size(max = 200) String email,
            @Size(max = 500) String addressLine,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Pattern(regexp = "^[0-9]{6}$", message = "must be a 6-digit PIN code") String pincode,
            @Size(max = 200) String emergencyContactName,
            @Pattern(regexp = "^[0-9+\\- ]{7,20}$", message = "must be a valid phone number") String emergencyContactPhone,
            @Size(max = 50) String emergencyContactRelation,
            @Pattern(regexp = "^[0-9]{2}-[0-9]{4}-[0-9]{4}-[0-9]{4}$", message = "must look like 12-3456-7890-1234") String abhaNumber,
            @Size(max = 50) String preferredLanguage,
            /** Optimistic-lock version (required on update). */
            Long version,
            /** Set true to register despite a possible-duplicate warning. */
            boolean confirmNotDuplicate) {
    }

    public record CurrentLocation(UUID admissionId, String admissionNumber, Admission.Status status,
                                  String wardName, String roomNumber, String bedNumber) {
    }

    public record PatientSummary(UUID id, String mrn, String fullName, String firstName, String lastName,
                                 LocalDate dateOfBirth, int age, Patient.Gender gender, String phone,
                                 Patient.Status status, boolean hasPortalAccount, CurrentLocation currentAdmission,
                                 Instant createdAt) {
    }

    public record PatientDetail(UUID id, String mrn, String firstName, String lastName, String fullName,
                                LocalDate dateOfBirth, int age, Patient.Gender gender, String bloodGroup,
                                String phone, String email, String addressLine, String city, String state,
                                String pincode, String emergencyContactName, String emergencyContactPhone,
                                String emergencyContactRelation, String abhaNumber, String preferredLanguage,
                                Patient.Status status, boolean hasPortalAccount, CurrentLocation currentAdmission,
                                Instant createdAt, Instant updatedAt, Long version) {
    }

    public record PortalAccountRequest(@NotBlank @Email @Size(max = 200) String email) {
    }

    public record CaregiverGrantRequest(
            @NotBlank @Email @Size(max = 200) String email,
            @Size(max = 200) String fullName,
            @Pattern(regexp = "^[0-9+\\- ]{7,20}$", message = "must be a valid phone number") String phone,
            @NotBlank @Size(max = 50) String relationship,
            @NotNull PatientAccessGrant.Scope scope,
            Instant expiresAt,
            @Size(max = 500) String notes) {
    }

    public record CaregiverGrantView(UUID id, UUID granteeUserId, String granteeName, String granteeEmail,
                                     String relationship, PatientAccessGrant.Scope scope, Instant grantedAt,
                                     Instant expiresAt, Instant revokedAt, boolean active) {
    }

    public record DuplicateCandidate(UUID id, String mrn, String fullName, LocalDate dateOfBirth, String phone) {
    }
}
