package com.careflow.hms.user;

import com.careflow.hms.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class UserDtos {

    private UserDtos() {
    }

    public record CreateStaffRequest(
            @NotBlank @Email @Size(max = 200) String email,
            @NotBlank @Size(max = 200) String fullName,
            @Pattern(regexp = "^[0-9+\\- ]{7,20}$", message = "must be a valid phone number") String phone,
            @NotNull Role role,
            UUID departmentId,
            @Size(max = 100) String designation,
            @Size(max = 200) String specialty,
            @Size(max = 100) String registrationNumber,
            @Size(max = 200) String qualification,
            @Size(max = 50) String employeeCode,
            List<UUID> wardIds,
            /** Optional temporary password (must meet policy); otherwise an invitation link is sent. */
            @Size(max = 128) String temporaryPassword) {
    }

    public record UpdateStaffRequest(
            @NotBlank @Size(max = 200) String fullName,
            @Pattern(regexp = "^[0-9+\\- ]{7,20}$", message = "must be a valid phone number") String phone,
            UUID departmentId,
            @Size(max = 100) String designation,
            @Size(max = 200) String specialty,
            @Size(max = 100) String registrationNumber,
            @Size(max = 200) String qualification,
            @Size(max = 50) String employeeCode,
            @NotNull Long version) {
    }

    public record StatusRequest(@NotNull User.Status status, @Size(max = 500) String reason) {
    }

    public record StaffView(UUID id, String email, String fullName, String phone, Role role, User.Status status,
                            boolean locked, Instant lastLoginAt, Instant createdAt, UUID departmentId,
                            String departmentName, String designation, String specialty, String registrationNumber,
                            String qualification, String employeeCode, List<String> wards, Long version) {
    }

    public record DirectoryEntry(UUID id, String fullName, Role role, UUID departmentId, String departmentName,
                                 String designation, String specialty) {
    }

    public record RoleMatrix(List<RoleInfo> roles, List<PermissionInfo> permissions) {
    }

    public record RoleInfo(Role role, String name, String description, Set<String> permissions, long userCount) {
    }

    public record PermissionInfo(String code, String category, String description) {
    }

    public record RolePermissionsRequest(@NotNull Set<String> permissions) {
    }
}
