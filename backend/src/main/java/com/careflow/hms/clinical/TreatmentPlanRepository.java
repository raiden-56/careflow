package com.careflow.hms.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TreatmentPlanRepository extends JpaRepository<TreatmentPlan, UUID> {

    List<TreatmentPlan> findByAdmissionIdOrderByPlanVersionDesc(UUID admissionId);

    Optional<TreatmentPlan> findByAdmissionIdAndStatus(UUID admissionId, TreatmentPlan.Status status);
}
