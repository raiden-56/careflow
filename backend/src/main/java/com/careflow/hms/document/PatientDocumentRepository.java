package com.careflow.hms.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientDocumentRepository extends JpaRepository<PatientDocument, UUID> {

    Optional<PatientDocument> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<PatientDocument> findByPatientIdAndStatusOrderByUploadedAtDesc(UUID patientId, PatientDocument.Status status);

    List<PatientDocument> findByAdmissionIdAndStatusOrderByUploadedAtDesc(UUID admissionId, PatientDocument.Status status);
}
