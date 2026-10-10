package com.careflow.hms.facility;

import com.careflow.hms.facility.FacilityDtos.BedHistoryEntry;
import com.careflow.hms.facility.FacilityDtos.BedRequest;
import com.careflow.hms.facility.FacilityDtos.BedStatusRequest;
import com.careflow.hms.facility.FacilityDtos.BedView;
import com.careflow.hms.facility.FacilityDtos.DepartmentRequest;
import com.careflow.hms.facility.FacilityDtos.DepartmentView;
import com.careflow.hms.facility.FacilityDtos.RoomRequest;
import com.careflow.hms.facility.FacilityDtos.RoomView;
import com.careflow.hms.facility.FacilityDtos.WardBoard;
import com.careflow.hms.facility.FacilityDtos.WardRequest;
import com.careflow.hms.facility.FacilityDtos.WardStaffRequest;
import com.careflow.hms.facility.FacilityDtos.WardStaffView;
import com.careflow.hms.facility.FacilityDtos.WardView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

@Tag(name = "Facility: departments, wards, rooms, beds")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FacilityController {

    private static final String STAFF = "hasAnyRole('HOSPITAL_ADMIN','DOCTOR','NURSE','BILLING','PHARMACIST')";

    private final FacilityService service;

    @GetMapping("/departments")
    @PreAuthorize(STAFF)
    public List<DepartmentView> departments() {
        return service.listDepartments();
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DEPARTMENT_MANAGE')")
    public DepartmentView createDepartment(@Valid @RequestBody DepartmentRequest body) {
        return service.saveDepartment(null, body);
    }

    @PutMapping("/departments/{id}")
    @PreAuthorize("hasAuthority('DEPARTMENT_MANAGE')")
    public DepartmentView updateDepartment(@PathVariable UUID id, @Valid @RequestBody DepartmentRequest body) {
        return service.saveDepartment(id, body);
    }

    @GetMapping("/wards")
    @PreAuthorize(STAFF)
    public List<WardView> wards() {
        return service.listWards();
    }

    @PostMapping("/wards")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('FACILITY_MANAGE')")
    public WardView createWard(@Valid @RequestBody WardRequest body) {
        return service.saveWard(null, body);
    }

    @PutMapping("/wards/{id}")
    @PreAuthorize("hasAuthority('FACILITY_MANAGE')")
    public WardView updateWard(@PathVariable UUID id, @Valid @RequestBody WardRequest body) {
        return service.saveWard(id, body);
    }

    @GetMapping("/wards/{wardId}/rooms")
    @PreAuthorize(STAFF)
    public List<RoomView> rooms(@PathVariable UUID wardId) {
        return service.listRooms(wardId);
    }

    @PostMapping("/wards/{wardId}/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('FACILITY_MANAGE')")
    public RoomView createRoom(@PathVariable UUID wardId, @Valid @RequestBody RoomRequest body) {
        return service.saveRoom(wardId, null, body);
    }

    @PutMapping("/wards/{wardId}/rooms/{roomId}")
    @PreAuthorize("hasAuthority('FACILITY_MANAGE')")
    public RoomView updateRoom(@PathVariable UUID wardId, @PathVariable UUID roomId, @Valid @RequestBody RoomRequest body) {
        return service.saveRoom(wardId, roomId, body);
    }

    @GetMapping("/wards/{wardId}/staff")
    @PreAuthorize("hasAnyAuthority('USER_MANAGE','FACILITY_MANAGE')")
    public List<WardStaffView> wardStaff(@PathVariable UUID wardId) {
        return service.wardStaff(wardId);
    }

    @PostMapping("/wards/{wardId}/staff")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('USER_MANAGE','FACILITY_MANAGE')")
    public WardStaffView assignWardStaff(@PathVariable UUID wardId, @Valid @RequestBody WardStaffRequest body) {
        return service.assignWardStaff(wardId, body.userId());
    }

    @DeleteMapping("/wards/{wardId}/staff/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyAuthority('USER_MANAGE','FACILITY_MANAGE')")
    public void removeWardStaff(@PathVariable UUID wardId, @PathVariable UUID assignmentId) {
        service.removeWardStaff(wardId, assignmentId);
    }

    @GetMapping("/beds/board")
    @PreAuthorize(STAFF)
    public List<WardBoard> bedBoard(@RequestParam(required = false) UUID wardId,
                                    @RequestParam(required = false) UUID departmentId,
                                    @RequestParam(required = false) Bed.Status status,
                                    @RequestParam(required = false) Bed.BedType bedType) {
        return service.bedBoard(wardId, departmentId, status, bedType);
    }

    @GetMapping("/beds/available")
    @PreAuthorize("hasAnyAuthority('BED_ASSIGN','TRANSFER_REQUEST','TRANSFER_COMPLETE','ADMISSION_MANAGE')")
    public List<BedView> availableBeds(@RequestParam(required = false) UUID wardId,
                                       @RequestParam(required = false) Bed.BedType bedType,
                                       @RequestParam(defaultValue = "false") boolean isolationOnly) {
        return service.availableBeds(wardId, bedType, isolationOnly);
    }

    @PostMapping("/beds")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('FACILITY_MANAGE')")
    public BedView createBed(@Valid @RequestBody BedRequest body) {
        return service.saveBed(null, body);
    }

    @PutMapping("/beds/{id}")
    @PreAuthorize("hasAuthority('FACILITY_MANAGE')")
    public BedView updateBed(@PathVariable UUID id, @Valid @RequestBody BedRequest body) {
        return service.saveBed(id, body);
    }

    @PatchMapping("/beds/{id}/status")
    @PreAuthorize("hasAuthority('BED_STATUS_MANAGE')")
    public BedView changeStatus(@PathVariable UUID id, @Valid @RequestBody BedStatusRequest body) {
        return service.changeBedStatus(id, body.status(), body.reason(), body.version());
    }

    @GetMapping("/beds/{id}/history")
    @PreAuthorize("hasAnyAuthority('FACILITY_MANAGE','BED_ASSIGN','BED_STATUS_MANAGE')")
    public List<BedHistoryEntry> bedHistory(@PathVariable UUID id) {
        return service.bedHistory(id);
    }
}
