package com.careflow.hms.admission;

import com.careflow.hms.common.ApiException;
import com.careflow.hms.facility.Bed;
import com.careflow.hms.facility.BedRepository;
import com.careflow.hms.facility.FacilityService;
import com.careflow.hms.facility.Ward;
import com.careflow.hms.facility.WardRepository;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Concurrency-safe bed allocation. Conflicting assignments are prevented at three levels:
 * <ol>
 *   <li>the bed row is locked (SELECT ... FOR UPDATE) for the duration of the transaction;</li>
 *   <li>status and open-assignment checks are made while holding the lock;</li>
 *   <li>partial unique indexes reject a second open assignment for a bed or an admission.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class BedAllocationService {

    private final BedRepository beds;
    private final WardRepository wards;
    private final BedAssignmentRepository assignments;
    private final PatientRepository patients;
    private final FacilityService facility;

    @Transactional(propagation = Propagation.MANDATORY)
    public BedAssignment assign(Admission admission, UUID bedId, UUID transferId, String notes) {
        UUID org = TenantContext.requireOrganizationId();
        Bed bed = beds.lockById(bedId, org).orElseThrow(() -> ApiException.notFound("Bed"));
        if (!bed.isActive()) {
            throw ApiException.rule("BED_INACTIVE", "Bed " + bed.getBedNumber() + " is not in service.");
        }
        if (bed.getStatus() != Bed.Status.AVAILABLE && bed.getStatus() != Bed.Status.RESERVED) {
            throw ApiException.conflict("BED_NOT_AVAILABLE",
                    "Bed " + bed.getBedNumber() + " is " + bed.getStatus().name().toLowerCase() + " and cannot be assigned.");
        }
        if (assignments.findByBedIdAndReleasedAtIsNull(bed.getId()).isPresent()) {
            throw ApiException.conflict("BED_ALREADY_ASSIGNED", "This bed is already assigned to another active admission.");
        }
        if (assignments.findByAdmissionIdAndReleasedAtIsNull(admission.getId()).isPresent()) {
            throw ApiException.conflict("ADMISSION_ALREADY_HAS_BED", "This admission already has an active bed. Use a transfer instead.");
        }
        Ward ward = wards.findById(bed.getWardId()).orElseThrow();
        if (!ward.isActive()) {
            throw ApiException.rule("WARD_INACTIVE", "Ward " + ward.getName() + " is not active.");
        }
        Patient patient = patients.findById(admission.getPatientId()).orElseThrow();
        if (ward.getGenderPolicy() == Ward.GenderPolicy.MALE && patient.getGender() == Patient.Gender.FEMALE
                || ward.getGenderPolicy() == Ward.GenderPolicy.FEMALE && patient.getGender() == Patient.Gender.MALE) {
            throw ApiException.rule("WARD_GENDER_POLICY", ward.getName() + " is a " + ward.getGenderPolicy().name().toLowerCase() + "-only ward.");
        }
        if (admission.isRequiresIsolation() && !bed.isIsolationCapable()) {
            throw ApiException.rule("ISOLATION_REQUIRED", "This patient requires an isolation-capable bed.");
        }
        BedAssignment ba = new BedAssignment();
        ba.setAdmissionId(admission.getId());
        ba.setBedId(bed.getId());
        ba.setAssignedAt(Instant.now());
        ba.setAssignedBy(TenantContext.actorId());
        ba.setTransferId(transferId);
        ba.setNotes(notes);
        ba = assignments.saveAndFlush(ba);
        facility.recordStatus(bed, Bed.Status.OCCUPIED, "Assigned to " + admission.getAdmissionNumber());
        return ba;
    }

    /** Releases the admission's current bed and moves the bed into the given turnover status. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Bed release(Admission admission, BedAssignment.ReleaseReason reason, Bed.Status turnoverStatus) {
        UUID org = TenantContext.requireOrganizationId();
        BedAssignment ba = assignments.findByAdmissionIdAndReleasedAtIsNull(admission.getId()).orElse(null);
        if (ba == null) {
            return null;
        }
        Bed bed = beds.lockById(ba.getBedId(), org).orElseThrow();
        ba.setReleasedAt(Instant.now());
        ba.setReleasedBy(TenantContext.actorId());
        ba.setReleaseReason(reason);
        assignments.saveAndFlush(ba);
        Bed.Status target = turnoverStatus == null ? Bed.Status.CLEANING : turnoverStatus;
        facility.recordStatus(bed, target, "Released from " + admission.getAdmissionNumber() + " (" + reason + ")");
        return bed;
    }
}
