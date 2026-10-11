package com.careflow.hms.consent;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientRequestRepository extends JpaRepository<PatientRequest, UUID> {

    List<PatientRequest> findByPatientIdOrderByRaisedAtDesc(UUID patientId);

    Page<PatientRequest> findByOrganizationId(UUID organizationId, Pageable pageable);

    Optional<PatientRequest> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("""
            select r from PatientRequest r
            where r.organizationId = :orgId
              and r.status in :statuses
              and (:type is null or r.requestType = :type)
              and (:patientId is null or r.patientId = :patientId)
            """)
    Page<PatientRequest> search(@Param("orgId") UUID orgId, @Param("statuses") Collection<PatientRequest.Status> statuses,
                                @Param("type") PatientRequest.RequestType type, @Param("patientId") UUID patientId,
                                Pageable pageable);
}
