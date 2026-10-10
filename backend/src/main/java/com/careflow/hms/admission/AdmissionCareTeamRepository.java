package com.careflow.hms.admission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdmissionCareTeamRepository extends JpaRepository<AdmissionCareTeam, UUID> {

    List<AdmissionCareTeam> findByAdmissionIdAndRemovedAtIsNull(UUID admissionId);

    Optional<AdmissionCareTeam> findByAdmissionIdAndUserIdAndRemovedAtIsNull(UUID admissionId, UUID userId);

    boolean existsByAdmissionIdAndUserIdAndRemovedAtIsNull(UUID admissionId, UUID userId);
}
