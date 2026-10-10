package com.careflow.hms.clinical;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ClinicalNoteRepository extends JpaRepository<ClinicalNote, UUID> {

    Page<ClinicalNote> findByAdmissionId(UUID admissionId, Pageable pageable);

    Page<ClinicalNote> findByAdmissionIdAndNoteTypeIn(UUID admissionId, Collection<ClinicalNote.NoteType> types, Pageable pageable);

    List<ClinicalNote> findByAdmissionIdOrderByAuthoredAtDesc(UUID admissionId);

    List<ClinicalNote> findByPatientIdAndReleasedToPatientTrueAndStatusOrderByAuthoredAtDesc(UUID patientId, ClinicalNote.Status status);
}
