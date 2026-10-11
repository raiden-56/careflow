package com.careflow.hms.platform;

import com.careflow.hms.org.Organization;
import com.careflow.hms.security.Role;
import com.careflow.hms.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlatformDtos {

    private PlatformDtos() {
    }

    public record OrganizationRow(UUID id, String code, String name, String legalName, String city, String state,
                                  String timezone, Organization.Status status, long staffUsers, long hospitalAdmins,
                                  long wards, long beds, Instant lastLoginAt, Instant createdAt) {
    }

    public record AdminAccount(UUID id, UUID organizationId, String organizationName, String organizationCode, String email,
                               String fullName, String phone, User.Status status, boolean locked, boolean invitationPending,
                               Instant lastLoginAt, Instant createdAt) {
    }

    public record OrganizationDetail(UUID id, String code, String name, String legalName, String timezone, String currency,
                                     String address, String city, String state, String phone, String email,
                                     String registrationNumber, Organization.Status status, Instant createdAt,
                                     Instant updatedAt, Long version, Map<String, Long> usersByRole, long wards, long beds,
                                     List<AdminAccount> administrators) {
    }

    public record CreateOrganizationRequest(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_-]{2,31}$", message = "use 3-32 upper-case letters, digits, - or _ starting with a letter") String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String legalName,
            @NotBlank @Size(max = 64) String timezone,
            @Size(max = 500) String address,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Pattern(regexp = "^$|^[0-9+\\- ]{7,20}$", message = "must be a valid phone number") String phone,
            @Email @Size(max = 200) String email,
            @Size(max = 100) String registrationNumber) {
    }

    public record UpdateOrganizationRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String legalName,
            @NotBlank @Size(max = 64) String timezone,
            @Size(max = 500) String address,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Pattern(regexp = "^$|^[0-9+\\- ]{7,20}$", message = "must be a valid phone number") String phone,
            @Email @Size(max = 200) String email,
            @Size(max = 100) String registrationNumber,
            @NotNull Long version) {
    }

    public record ReasonRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    public record CreateAdminRequest(
            @NotBlank @Email @Size(max = 200) String email,
            @NotBlank @Size(max = 200) String fullName,
            @Pattern(regexp = "^$|^[0-9+\\- ]{7,20}$", message = "must be a valid phone number") String phone,
            @Size(max = 100) String designation) {
    }

    public record AdminStatusRequest(@NotNull User.Status status, @Size(max = 500) String reason) {
    }

    public record PermissionInfo(String code, String category, String description) {
    }

    public record TemplateCatalogue(Map<Role, Set<String>> defaults, List<PermissionInfo> permissions, List<RoleInfo> roles) {
    }

    public record RoleInfo(Role role, String name, String description) {
    }

    public record OrgRoleTemplate(Role role, String name, Set<String> current, Set<String> defaults, Set<String> added,
                                  Set<String> removed, boolean matchesDefault, long users) {
    }

    public record OrgRoleMatrix(UUID organizationId, String organizationName, List<OrgRoleTemplate> roles) {
    }

    public record SecurityEvent(UUID id, Instant occurredAt, UUID organizationId, String organizationName, String actorName,
                                String actorRole, String action, String category, String outcome, String summary,
                                String ipAddress) {
    }

    public record SecuritySummary(long failedLogins24h, long lockouts24h, long deniedAccess24h, long sessionReuse7d,
                                  long failedLogins7d, long deniedAccess7d) {
    }

    public record IntegrationRow(UUID organizationId, String organizationName, String organizationCode, String integrationType,
                                 String label, String mode, String endpointUrl, boolean secretReferenced, String adapterStatus,
                                 Instant updatedAt) {
    }

    public record Overview(long organizations, long activeOrganizations, long suspendedOrganizations, long staffUsers,
                           long hospitalAdmins, long activeSessions, SecuritySummary security,
                           List<OrganizationRow> recentOrganizations, Map<String, Long> integrationModes) {
    }

    public record FlywayMigration(Integer rank, String version, String description, Instant installedOn, boolean success,
                                  Integer executionTimeMs) {
    }

    public record Health(String status, Instant checkedAt, boolean databaseUp, String databaseVersion, long databaseLatencyMs,
                         String flywayCurrentVersion, int flywayApplied, boolean flywayAllSucceeded,
                         List<FlywayMigration> migrations, long organizations, long users, long activeSessions,
                         List<ScheduledJobMonitor.JobRun> jobs, long uptimeSeconds, long heapUsedMb, long heapMaxMb,
                         int availableProcessors, int liveThreads, String javaVersion, boolean demoMode,
                         boolean devOutboxEnabled) {
    }
}
