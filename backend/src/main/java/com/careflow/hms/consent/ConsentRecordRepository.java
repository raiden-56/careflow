package com.careflow.hms.consent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {

    List<ConsentRecord> findByPatientIdOrderByGrantedAtDesc(UUID patientId);
}
