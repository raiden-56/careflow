package com.careflow.hms.medication;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MedicationOrderRepository extends JpaRepository<MedicationOrder, UUID> {

    List<MedicationOrder> findByAdmissionIdOrderByPrescribedAtDesc(UUID admissionId);

    List<MedicationOrder> findByAdmissionIdAndStatusIn(UUID admissionId, Collection<MedicationOrder.Status> statuses);

    List<MedicationOrder> findByOrganizationIdAndStatus(UUID organizationId, MedicationOrder.Status status);

    List<MedicationOrder> findByPatientIdAndReleasedToPatientTrueOrderByPrescribedAtDesc(UUID patientId);

    @Query("select o from MedicationOrder o where o.organizationId = :orgId and o.status in :statuses")
    Page<MedicationOrder> findByStatuses(@Param("orgId") UUID orgId,
                                         @Param("statuses") Collection<MedicationOrder.Status> statuses,
                                         Pageable pageable);

    @Query("select o from MedicationOrder o where o.admissionId in :ids and o.status in :statuses")
    Page<MedicationOrder> findForAdmissions(@Param("ids") Collection<UUID> admissionIds,
                                            @Param("statuses") Collection<MedicationOrder.Status> statuses, Pageable pageable);

    List<MedicationOrder> findByAdmissionIdInAndStatusIn(Collection<UUID> admissionIds, Collection<MedicationOrder.Status> statuses);

    /** ACTIVE orders whose end time has passed (to be completed by the schedule job). */
    @Query("select o from MedicationOrder o where o.organizationId = :orgId and o.status = 'ACTIVE' and o.endAt is not null and o.endAt < :now")
    List<MedicationOrder> findExpired(@Param("orgId") UUID orgId, @Param("now") Instant now);
}
