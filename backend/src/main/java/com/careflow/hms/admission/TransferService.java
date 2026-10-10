package com.careflow.hms.admission;

import com.careflow.hms.admission.AdmissionDtos.TransferRequest;
import com.careflow.hms.admission.AdmissionDtos.TransferView;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.facility.Bed;
import com.careflow.hms.facility.BedRepository;
import com.careflow.hms.facility.WardRepository;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Permissions;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** Inter-ward / inter-bed transfers: request -> approve (bed chosen) -> complete (atomic bed move). */
@Service
@RequiredArgsConstructor
public class TransferService {

    private static final EnumSet<Transfer.Status> ACTIVE = EnumSet.of(Transfer.Status.REQUESTED, Transfer.Status.APPROVED);

    private final TransferRepository transfers;
    private final AdmissionRepository admissions;
    private final BedAssignmentRepository bedAssignments;
    private final BedRepository beds;
    private final WardRepository wards;
    private final BedAllocationService bedAllocation;
    private final AdmissionService admissionService;
    private final AccessPolicy access;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;

    @Transactional
    public TransferView request(UUID admissionId, TransferRequest r) {
        CurrentUser u = TenantContext.requireUser();
        Admission a = u.has(Permissions.ADMISSION_MANAGE) || u.has(Permissions.TRANSFER_COMPLETE)
                ? admissionService.lockAdmission(admissionId)
                : access.requireAdmission(admissionId, AccessPolicy.Scope.CLINICAL);
        if (a.getStatus() != Admission.Status.ADMITTED && a.getStatus() != Admission.Status.DISCHARGE_IN_PROGRESS) {
            throw ApiException.rule("ADMISSION_NOT_ACTIVE", "Only admitted patients with a bed can be transferred.");
        }
        BedAssignment current = bedAssignments.findByAdmissionIdAndReleasedAtIsNull(a.getId())
                .orElseThrow(() -> ApiException.rule("NO_CURRENT_BED", "The patient has no current bed; assign one instead."));
        if (transfers.existsByAdmissionIdAndStatusIn(a.getId(), ACTIVE)) {
            throw ApiException.conflict("TRANSFER_PENDING", "A transfer is already pending for this admission.");
        }
        if (r.toWardId() == null && r.toBedId() == null) {
            throw ApiException.badRequest("DESTINATION_REQUIRED", "Choose a destination ward or bed.");
        }
        if (r.toWardId() != null) {
            wards.findByIdAndOrganizationId(r.toWardId(), a.getOrganizationId()).orElseThrow(() -> ApiException.notFound("Ward"));
        }
        if (r.toBedId() != null) {
            Bed target = beds.findByIdAndOrganizationId(r.toBedId(), a.getOrganizationId()).orElseThrow(() -> ApiException.notFound("Bed"));
            if (target.getId().equals(current.getBedId())) {
                throw ApiException.rule("SAME_BED", "The patient is already in this bed.");
            }
        }
        Transfer t = new Transfer();
        t.setAdmissionId(a.getId());
        t.setFromBedId(current.getBedId());
        t.setToWardId(r.toWardId() != null ? r.toWardId()
                : r.toBedId() != null ? beds.findById(r.toBedId()).map(Bed::getWardId).orElse(null) : null);
        t.setToBedId(r.toBedId());
        t.setReason(r.reason());
        t.setPriority(r.priority() == null ? Transfer.Priority.ROUTINE : r.priority());
        t.setStatus(Transfer.Status.REQUESTED);
        t.setRequestedBy(u.userId());
        t = transfers.saveAndFlush(t);
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("TRANSFER_REQUESTED").category(TimelineEvent.Category.TRANSFER)
                .title("Transfer requested").detail(r.reason()).status("REQUESTED")
                .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("TRANSFER").entityId(t.getId()).build());
        notifications.notifyRole(Role.HOSPITAL_ADMIN, NotificationRequest.builder()
                .category("BED").priority(t.getPriority() == Transfer.Priority.URGENT ? Notification.Priority.HIGH : Notification.Priority.NORMAL)
                .title("Transfer request: " + a.getAdmissionNumber()).message(r.reason())
                .link("/admin/transfers").entityType("TRANSFER").entityId(t.getId())
                .dedupeKey("TRANSFER_REQ:" + t.getId()).build());
        audit.record("TRANSFER_REQUESTED", "TRANSFER", t.getId(), a.getPatientId(), r.reason());
        return view(t);
    }

    @Transactional
    public TransferView approve(UUID transferId, UUID toBedId, String notes) {
        Transfer t = require(transferId);
        if (t.getStatus() != Transfer.Status.REQUESTED) {
            throw ApiException.rule("INVALID_TRANSFER_STATE", "Only requested transfers can be approved.");
        }
        UUID bedId = toBedId != null ? toBedId : t.getToBedId();
        if (bedId == null) {
            throw ApiException.badRequest("BED_REQUIRED", "Choose the destination bed to approve the transfer.");
        }
        Bed bed = beds.findByIdAndOrganizationId(bedId, t.getOrganizationId()).orElseThrow(() -> ApiException.notFound("Bed"));
        if (bed.getStatus() != Bed.Status.AVAILABLE && bed.getStatus() != Bed.Status.RESERVED) {
            throw ApiException.conflict("BED_NOT_AVAILABLE", "The destination bed is " + bed.getStatus().name().toLowerCase() + ".");
        }
        t.setToBedId(bedId);
        t.setToWardId(bed.getWardId());
        t.setStatus(Transfer.Status.APPROVED);
        t.setDecidedBy(TenantContext.actorId());
        t.setDecidedAt(Instant.now());
        t.setDecisionNotes(notes);
        audit.record("TRANSFER_APPROVED", "TRANSFER", t.getId(), null, notes);
        return view(t);
    }

    /** Atomically moves the patient: releases the old bed (to cleaning) and occupies the new bed. */
    @Transactional
    public TransferView complete(UUID transferId) {
        Transfer t = require(transferId);
        if (t.getStatus() != Transfer.Status.APPROVED && t.getStatus() != Transfer.Status.REQUESTED) {
            throw ApiException.rule("INVALID_TRANSFER_STATE", "This transfer cannot be completed.");
        }
        if (t.getToBedId() == null) {
            throw ApiException.badRequest("BED_REQUIRED", "Approve the transfer with a destination bed first.");
        }
        Admission a = admissionService.lockAdmission(t.getAdmissionId());
        if (!a.getStatus().isOpen()) {
            throw ApiException.rule("ADMISSION_CLOSED", "The admission is no longer active.");
        }
        // Lock beds in a consistent order to avoid deadlocks between concurrent transfers.
        UUID first = t.getFromBedId() != null && t.getFromBedId().compareTo(t.getToBedId()) < 0 ? t.getFromBedId() : t.getToBedId();
        beds.lockById(first, a.getOrganizationId());
        bedAllocation.release(a, BedAssignment.ReleaseReason.TRANSFER, Bed.Status.CLEANING);
        bedAllocation.assign(a, t.getToBedId(), t.getId(), "Transfer");
        t.setStatus(Transfer.Status.COMPLETED);
        t.setCompletedBy(TenantContext.actorId());
        t.setCompletedAt(Instant.now());
        if (t.getDecidedAt() == null) {
            t.setDecidedBy(TenantContext.actorId());
            t.setDecidedAt(Instant.now());
        }
        String where = admissionService.describeBed(t.getToBedId());
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("PATIENT_TRANSFERRED").category(TimelineEvent.Category.TRANSFER)
                .title("Transferred to " + where).detail(t.getReason()).status("COMPLETED")
                .visibility(TimelineEvent.Visibility.PATIENT).entityType("TRANSFER").entityId(t.getId()).build());
        notifications.notifyCareTeam(a.getId(), null, NotificationRequest.builder()
                .category("BED").title("Patient transferred").message(a.getAdmissionNumber() + " moved to " + where)
                .link("/doctor/patients/" + a.getId()).entityType("ADMISSION").entityId(a.getId())
                .dedupeKey("TRANSFER_DONE:" + t.getId()).build());
        notifications.notifyWardNurses(a.getId(), NotificationRequest.builder()
                .category("ASSIGNMENT").priority(Notification.Priority.HIGH).title("Transfer arriving on ward")
                .message(a.getAdmissionNumber() + " -> " + where).link("/nurse/patients/" + a.getId())
                .entityType("ADMISSION").entityId(a.getId()).dedupeKey("TRANSFER_ARRIVE:" + t.getId()).build());
        audit.record("TRANSFER_COMPLETED", "TRANSFER", t.getId(), a.getPatientId(), "Moved to " + where);
        return view(t);
    }

    @Transactional
    public TransferView reject(UUID transferId, String notes, boolean cancel) {
        Transfer t = require(transferId);
        if (!ACTIVE.contains(t.getStatus())) {
            throw ApiException.rule("INVALID_TRANSFER_STATE", "This transfer is already closed.");
        }
        CurrentUser u = TenantContext.requireUser();
        if (cancel && !u.userId().equals(t.getRequestedBy()) && !u.has(Permissions.TRANSFER_COMPLETE)) {
            throw ApiException.forbidden("Only the requester or bed management can cancel this transfer.");
        }
        if (!cancel && !u.has(Permissions.TRANSFER_COMPLETE)) {
            throw ApiException.forbidden("Only bed management can reject transfers.");
        }
        t.setStatus(cancel ? Transfer.Status.CANCELLED : Transfer.Status.REJECTED);
        t.setDecidedBy(u.userId());
        t.setDecidedAt(Instant.now());
        t.setDecisionNotes(notes);
        audit.record(cancel ? "TRANSFER_CANCELLED" : "TRANSFER_REJECTED", "TRANSFER", t.getId(), null, notes);
        return view(t);
    }

    @Transactional(readOnly = true)
    public PageResponse<TransferView> list(Collection<Transfer.Status> statuses, int page, int size) {
        CurrentUser u = TenantContext.requireUser();
        Collection<Transfer.Status> st = statuses == null || statuses.isEmpty() ? EnumSet.allOf(Transfer.Status.class) : statuses;
        var result = transfers.findByStatuses(u.organizationId(), st,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "requestedAt")));
        List<TransferView> content = admissionService.transferViews(result.getContent());
        // Clinicians only see transfers for patients they can access.
        if (!u.has(Permissions.TRANSFER_COMPLETE) && !u.has(Permissions.ADMISSION_MANAGE)) {
            content = content.stream().filter(tv -> admissions.findById(tv.admissionId())
                    .map(a -> access.canAccessAdmission(u, a, AccessPolicy.Scope.CLINICAL)).orElse(false)).toList();
        }
        return new PageResponse<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    private Transfer require(UUID id) {
        return transfers.findByIdAndOrganizationId(id, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Transfer"));
    }

    private TransferView view(Transfer t) {
        return admissionService.transferViews(List.of(t)).get(0);
    }
}
