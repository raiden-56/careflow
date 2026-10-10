package com.careflow.hms.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ClinicalAlertRepository extends JpaRepository<ClinicalAlert, UUID> {

    List<ClinicalAlert> findByAdmissionIdOrderByRaisedAtDesc(UUID admissionId);

    List<ClinicalAlert> findByAdmissionIdInAndStatusInOrderByRaisedAtDesc(Collection<UUID> admissionIds,
                                                                          Collection<ClinicalAlert.Status> statuses);

    List<ClinicalAlert> findByOrganizationIdAndStatusIn(UUID organizationId, Collection<ClinicalAlert.Status> statuses);

    long countByAdmissionIdAndStatusIn(UUID admissionId, Collection<ClinicalAlert.Status> statuses);
}
