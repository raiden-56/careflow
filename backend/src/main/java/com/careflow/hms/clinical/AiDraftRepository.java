package com.careflow.hms.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AiDraftRepository extends JpaRepository<AiDraft, UUID> {

    List<AiDraft> findByAdmissionIdOrderByCreatedAtDesc(UUID admissionId);
}
