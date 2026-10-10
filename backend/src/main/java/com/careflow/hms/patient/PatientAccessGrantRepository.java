package com.careflow.hms.patient;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientAccessGrantRepository extends JpaRepository<PatientAccessGrant, UUID> {

    List<PatientAccessGrant> findByGranteeUserIdAndRevokedAtIsNull(UUID granteeUserId);

    List<PatientAccessGrant> findByPatientIdOrderByGrantedAtDesc(UUID patientId);

    Optional<PatientAccessGrant> findByPatientIdAndGranteeUserIdAndRevokedAtIsNull(UUID patientId, UUID granteeUserId);
}
