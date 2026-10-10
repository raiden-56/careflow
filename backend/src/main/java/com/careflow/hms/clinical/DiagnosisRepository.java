package com.careflow.hms.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DiagnosisRepository extends JpaRepository<Diagnosis, UUID> {

    List<Diagnosis> findByAdmissionIdOrderByRecordedAtDesc(UUID admissionId);

    List<Diagnosis> findByPatientIdOrderByRecordedAtDesc(UUID patientId);
}
