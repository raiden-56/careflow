package com.careflow.hms.discharge;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DischargeWorkflowRepository extends JpaRepository<DischargeWorkflow, UUID> {

    Optional<DischargeWorkflow> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from DischargeWorkflow w where w.id = :id and w.organizationId = :orgId")
    Optional<DischargeWorkflow> lockById(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Query("select w from DischargeWorkflow w where w.admissionId = :admissionId and w.status in ('IN_PROGRESS','READY')")
    Optional<DischargeWorkflow> findOpenByAdmission(@Param("admissionId") UUID admissionId);

    List<DischargeWorkflow> findByAdmissionIdOrderByInitiatedAtDesc(UUID admissionId);

    List<DischargeWorkflow> findByAdmissionIdInOrderByInitiatedAtDesc(Collection<UUID> admissionIds);

    List<DischargeWorkflow> findByOrganizationIdAndStatusIn(UUID organizationId, Collection<DischargeWorkflow.Status> statuses);

    List<DischargeWorkflow> findByOrganizationIdAndStatusAndCompletedAtAfterOrderByCompletedAtDesc(
            UUID organizationId, DischargeWorkflow.Status status, Instant after);

    List<DischargeWorkflow> findByOrganizationIdAndStatusAndInitiatedAtAfterOrderByInitiatedAtDesc(
            UUID organizationId, DischargeWorkflow.Status status, Instant after);

    List<DischargeWorkflow> findByOrganizationIdAndInitiatedAtBetween(UUID organizationId, Instant from, Instant to);
}
