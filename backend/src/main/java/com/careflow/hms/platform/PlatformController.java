package com.careflow.hms.platform;

import com.careflow.hms.common.PageResponse;
import com.careflow.hms.org.Organization;
import com.careflow.hms.platform.PlatformDtos.AdminAccount;
import com.careflow.hms.platform.PlatformDtos.AdminStatusRequest;
import com.careflow.hms.platform.PlatformDtos.CreateAdminRequest;
import com.careflow.hms.platform.PlatformDtos.CreateOrganizationRequest;
import com.careflow.hms.platform.PlatformDtos.Health;
import com.careflow.hms.platform.PlatformDtos.IntegrationRow;
import com.careflow.hms.platform.PlatformDtos.OrgRoleMatrix;
import com.careflow.hms.platform.PlatformDtos.OrganizationDetail;
import com.careflow.hms.platform.PlatformDtos.OrganizationRow;
import com.careflow.hms.platform.PlatformDtos.Overview;
import com.careflow.hms.platform.PlatformDtos.ReasonRequest;
import com.careflow.hms.platform.PlatformDtos.SecurityEvent;
import com.careflow.hms.platform.PlatformDtos.SecuritySummary;
import com.careflow.hms.platform.PlatformDtos.TemplateCatalogue;
import com.careflow.hms.platform.PlatformDtos.UpdateOrganizationRequest;
import com.careflow.hms.security.Role;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Super-administrator platform APIs. Every endpoint requires the SUPER_ADMIN role plus the relevant permission. */
@Tag(name = "Platform administration")
@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformController {

    private static final String ORGS = "hasRole('SUPER_ADMIN') and hasAuthority('ORG_MANAGE')";
    private static final String USERS = "hasRole('SUPER_ADMIN') and hasAuthority('USER_MANAGE')";
    private static final String ROLES = "hasRole('SUPER_ADMIN') and hasAuthority('ROLE_MANAGE')";
    private static final String AUDIT = "hasRole('SUPER_ADMIN') and hasAuthority('PLATFORM_AUDIT_READ')";
    private static final String INTEGRATIONS = "hasRole('SUPER_ADMIN') and hasAuthority('INTEGRATION_MANAGE')";

    private final PlatformService service;

    @GetMapping("/overview")
    @PreAuthorize(ORGS)
    public Overview overview() {
        return service.overview();
    }

    // ------------------------------------------------------------------ organizations

    @GetMapping("/organizations")
    @PreAuthorize(ORGS)
    public PageResponse<OrganizationRow> organizations(@RequestParam(required = false) String q,
                                                       @RequestParam(required = false) Organization.Status status,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return service.organizationRows(q, status, page, size);
    }

    @PostMapping("/organizations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ORGS)
    public OrganizationDetail create(@Valid @RequestBody CreateOrganizationRequest body) {
        return service.create(body);
    }

    @GetMapping("/organizations/{id}")
    @PreAuthorize(ORGS)
    public OrganizationDetail organization(@PathVariable UUID id) {
        return service.organization(id);
    }

    @PutMapping("/organizations/{id}")
    @PreAuthorize(ORGS)
    public OrganizationDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateOrganizationRequest body) {
        return service.update(id, body);
    }

    @PostMapping("/organizations/{id}/suspend")
    @PreAuthorize(ORGS)
    public OrganizationDetail suspend(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return service.suspend(id, body.reason());
    }

    @PostMapping("/organizations/{id}/reactivate")
    @PreAuthorize(ORGS)
    public OrganizationDetail reactivate(@PathVariable UUID id) {
        return service.reactivate(id);
    }

    // ------------------------------------------------------------------ hospital administrators

    @GetMapping("/admins")
    @PreAuthorize(USERS)
    public PageResponse<AdminAccount> admins(@RequestParam(required = false) UUID organizationId,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return service.admins(organizationId, q, page, size);
    }

    @PostMapping("/organizations/{id}/admins")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(USERS)
    public AdminAccount createAdmin(@PathVariable UUID id, @Valid @RequestBody CreateAdminRequest body) {
        return service.createAdmin(id, body);
    }

    @PostMapping("/admins/{userId}/status")
    @PreAuthorize(USERS)
    public AdminAccount adminStatus(@PathVariable UUID userId, @Valid @RequestBody AdminStatusRequest body) {
        return service.changeAdminStatus(userId, body.status(), body.reason());
    }

    @PostMapping("/admins/{userId}/invite")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(USERS)
    public void resendInvite(@PathVariable UUID userId) {
        service.resendAdminInvite(userId);
    }

    // ------------------------------------------------------------------ permission templates

    @GetMapping("/permission-templates")
    @PreAuthorize(ROLES)
    public TemplateCatalogue templates() {
        return service.templates();
    }

    @GetMapping("/organizations/{id}/roles")
    @PreAuthorize(ROLES)
    public OrgRoleMatrix orgRoles(@PathVariable UUID id) {
        return service.orgRoles(id);
    }

    @PostMapping("/organizations/{id}/roles/{role}/reset")
    @PreAuthorize(ROLES)
    public OrgRoleMatrix resetRole(@PathVariable UUID id, @PathVariable Role role) {
        return service.resetRole(id, role);
    }

    // ------------------------------------------------------------------ security, integrations, health

    @GetMapping("/security-events")
    @PreAuthorize(AUDIT)
    public PageResponse<SecurityEvent> securityEvents(@RequestParam(required = false) UUID organizationId,
                                                      @RequestParam(required = false) String category,
                                                      @RequestParam(required = false) String outcome,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                                      @RequestParam(required = false) String q,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "25") int size) {
        return service.securityEvents(organizationId, category, outcome, from, to, q, page, size);
    }

    @GetMapping("/security-summary")
    @PreAuthorize(AUDIT)
    public SecuritySummary securitySummary() {
        return service.securitySummary();
    }

    @GetMapping("/integrations")
    @PreAuthorize(INTEGRATIONS)
    public List<IntegrationRow> integrations(@RequestParam(required = false) UUID organizationId) {
        return service.integrations(organizationId);
    }

    @GetMapping("/health")
    @PreAuthorize(ORGS)
    public Health health() {
        return service.health();
    }
}
