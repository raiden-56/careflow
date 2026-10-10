package com.careflow.hms.user;

import com.careflow.hms.common.PageResponse;
import com.careflow.hms.security.Role;
import com.careflow.hms.user.UserDtos.CreateStaffRequest;
import com.careflow.hms.user.UserDtos.DirectoryEntry;
import com.careflow.hms.user.UserDtos.RoleMatrix;
import com.careflow.hms.user.UserDtos.RolePermissionsRequest;
import com.careflow.hms.user.UserDtos.StaffView;
import com.careflow.hms.user.UserDtos.StatusRequest;
import com.careflow.hms.user.UserDtos.UpdateStaffRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

import java.util.List;
import java.util.UUID;

@Tag(name = "Staff, roles and permissions")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StaffController {

    private final StaffService service;

    @GetMapping("/staff")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public PageResponse<StaffView> search(@RequestParam(required = false) Role role,
                                          @RequestParam(required = false) User.Status status,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @RequestParam(required = false) String sort) {
        return service.search(role, status, q, page, size, sort);
    }

    @PostMapping("/staff")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public StaffView create(@Valid @RequestBody CreateStaffRequest body) {
        return service.create(body);
    }

    @GetMapping("/staff/{id}")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public StaffView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/staff/{id}")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public StaffView update(@PathVariable UUID id, @Valid @RequestBody UpdateStaffRequest body) {
        return service.update(id, body);
    }

    @PostMapping("/staff/{id}/status")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public StaffView status(@PathVariable UUID id, @Valid @RequestBody StatusRequest body) {
        return service.changeStatus(id, body.status(), body.reason());
    }

    @PostMapping("/staff/{id}/invite")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public void invite(@PathVariable UUID id) {
        service.resendInvite(id);
    }

    /** Minimal staff directory (names/roles/departments) for assignment pickers. */
    @GetMapping("/staff/directory")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','NURSE','BILLING','PHARMACIST')")
    public List<DirectoryEntry> directory(@RequestParam(required = false) Role role,
                                          @RequestParam(required = false) UUID departmentId) {
        return service.directory(role, departmentId);
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public RoleMatrix roles() {
        return service.roleMatrix();
    }

    @PutMapping("/roles/{role}/permissions")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public RoleMatrix updateRole(@PathVariable Role role, @Valid @RequestBody RolePermissionsRequest body) {
        return service.updateRolePermissions(role, body.permissions());
    }
}
