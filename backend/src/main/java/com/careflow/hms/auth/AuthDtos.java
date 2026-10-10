package com.careflow.hms.auth;

import com.careflow.hms.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank @Email @Size(max = 200) String email,
                               @NotBlank @Size(max = 128) String password) {
    }

    public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 200) String email) {
    }

    public record AccountRecoveryRequest(@NotBlank @Email @Size(max = 200) String email,
                                         @Size(max = 500) String reason) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(max = 200) String token,
                                       @NotBlank @Size(max = 128) String newPassword) {
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
                                        @NotBlank @Size(max = 128) String newPassword) {
    }

    public record OrganizationInfo(UUID id, String code, String name, String timezone, String currency) {
    }

    public record WardInfo(UUID id, String code, String name) {
    }

    public record StaffInfo(String designation, UUID departmentId, String departmentName, String specialty,
                            String employeeCode, List<WardInfo> wards) {
    }

    public record PatientLink(UUID patientId, String name, String mrn, String relationship, String scope) {
    }

    public record MeResponse(UUID id, String email, String fullName, String phone, Role role,
                             Set<String> permissions, boolean mustChangePassword,
                             OrganizationInfo organization, StaffInfo staff,
                             UUID patientId, List<PatientLink> accessiblePatients) {
    }
}
