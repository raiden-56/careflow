package com.careflow.hms.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProcedureRecordRepository extends JpaRepository<ProcedureRecord, UUID> {

    List<ProcedureRecord> findByAdmissionIdOrderByCreatedAtDesc(UUID admissionId);
}
