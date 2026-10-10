package com.careflow.hms.facility;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FacilityDtos {

    private FacilityDtos() {
    }

    public record DepartmentRequest(@NotBlank @Pattern(regexp = "^[A-Z0-9_-]{2,32}$", message = "use 2-32 upper-case letters, digits, - or _") String code,
                                    @NotBlank @Size(max = 200) String name,
                                    @Size(max = 200) String specialty,
                                    @Size(max = 1000) String description,
                                    UUID headUserId,
                                    Boolean active) {
    }

    public record DepartmentView(UUID id, String code, String name, String specialty, String description,
                                 UUID headUserId, String headName, boolean active, long staffCount, long wardCount) {
    }

    public record WardRequest(@NotBlank @Pattern(regexp = "^[A-Z0-9_-]{2,32}$", message = "use 2-32 upper-case letters, digits, - or _") String code,
                              @NotBlank @Size(max = 200) String name,
                              @NotNull Ward.WardType wardType,
                              UUID departmentId,
                              @Size(max = 20) String floor,
                              Ward.GenderPolicy genderPolicy,
                              Boolean active) {
    }

    public record WardView(UUID id, String code, String name, Ward.WardType wardType, UUID departmentId,
                           String departmentName, String floor, Ward.GenderPolicy genderPolicy, boolean active,
                           int totalBeds, int available, int occupied, int reserved, int cleaning, int maintenance) {
    }

    public record RoomRequest(@NotBlank @Size(max = 20) String roomNumber, @NotNull Room.RoomType roomType, Boolean active) {
    }

    public record RoomView(UUID id, UUID wardId, String roomNumber, Room.RoomType roomType, boolean active) {
    }

    public record BedRequest(@NotNull UUID roomId, @NotBlank @Size(max = 20) String bedNumber,
                             @NotNull Bed.BedType bedType, boolean isolationCapable, boolean oxygenSupported,
                             @Size(max = 500) String notes, Boolean active) {
    }

    public record BedStatusRequest(@NotNull Bed.Status status, @Size(max = 500) String reason, Long version) {
    }

    public record Occupant(UUID admissionId, String admissionNumber, UUID patientId, String patientName, String mrn,
                           String gender, Instant since, boolean requiresIsolation, String attendingDoctor,
                           String admissionStatus) {
    }

    public record BedView(UUID id, String bedNumber, Bed.BedType bedType, Bed.Status status, boolean isolationCapable,
                          boolean oxygenSupported, String notes, Instant statusChangedAt, boolean active, Long version,
                          UUID roomId, String roomNumber, Room.RoomType roomType, UUID wardId, String wardName,
                          Occupant occupant) {
    }

    public record RoomBoard(UUID id, String roomNumber, Room.RoomType roomType, List<BedView> beds) {
    }

    public record WardBoard(UUID id, String code, String name, Ward.WardType wardType, String departmentName,
                            String floor, Ward.GenderPolicy genderPolicy, List<RoomBoard> rooms) {
    }

    public record BedHistoryEntry(Instant at, String kind, String description, String actor) {
    }

    public record WardStaffRequest(@NotNull UUID userId) {
    }

    public record WardStaffView(UUID assignmentId, UUID userId, String fullName, String role, Instant assignedAt) {
    }
}
