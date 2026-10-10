package com.careflow.hms.patient;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MedicalHistoryRepository extends JpaRepository<MedicalHistory, UUID> {

    List<MedicalHistory> findByPatientIdOrderByRecordedAtDesc(UUID patientId);
}
