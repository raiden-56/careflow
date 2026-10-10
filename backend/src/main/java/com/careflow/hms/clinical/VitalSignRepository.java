package com.careflow.hms.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VitalSignRepository extends JpaRepository<VitalSign, UUID> {

    List<VitalSign> findByAdmissionIdAndRecordedAtBetweenOrderByRecordedAtAsc(UUID admissionId, Instant from, Instant to);

    List<VitalSign> findByAdmissionIdOrderByRecordedAtAsc(UUID admissionId);

    Optional<VitalSign> findFirstByAdmissionIdAndEnteredInErrorFalseOrderByRecordedAtDesc(UUID admissionId);
}
