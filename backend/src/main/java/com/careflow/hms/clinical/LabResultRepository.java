package com.careflow.hms.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LabResultRepository extends JpaRepository<LabResult, UUID> {

    List<LabResult> findByLabOrderIdOrderByComponent(UUID labOrderId);

    List<LabResult> findByLabOrderIdIn(Collection<UUID> labOrderIds);

    List<LabResult> findByAdmissionIdOrderByResultedAtDesc(UUID admissionId);

    List<LabResult> findByPatientIdAndReleasedToPatientTrueOrderByResultedAtDesc(UUID patientId);
}
