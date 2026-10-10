package com.careflow.hms.consent;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PatientRequestRepository extends JpaRepository<PatientRequest, UUID> {

    List<PatientRequest> findByPatientIdOrderByRaisedAtDesc(UUID patientId);

    Page<PatientRequest> findByOrganizationId(UUID organizationId, Pageable pageable);
}
