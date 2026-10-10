package com.careflow.hms.medication;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
