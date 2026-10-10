package com.careflow.hms.discharge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DischargeWorkflowRepository extends JpaRepository<DischargeWorkflow, UUID> {

    Optional<DischargeWorkflow> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("select w from DischargeWorkflow w where w.admissionId = :admissionId and w.status in ('IN_PROGRESS','READY')")
    Optional<DischargeWorkflow> findOpenByAdmission(@Param("admissionId") UUID admissionId);

    List<DischargeWorkflow> findByAdmissionIdOrderByInitiatedAtDesc(UUID admissionId);

    List<DischargeWorkflow> findByOrganizationIdAndStatusIn(UUID organizationId, Collection<DischargeWorkflow.Status> statuses);
}
