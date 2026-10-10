package com.careflow.hms.patient;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AllergyRepository extends JpaRepository<Allergy, UUID> {

    List<Allergy> findByPatientIdOrderByRecordedAtDesc(UUID patientId);

    List<Allergy> findByPatientIdAndStatus(UUID patientId, Allergy.Status status);

    List<Allergy> findByPatientIdInAndStatus(Collection<UUID> patientIds, Allergy.Status status);
}
