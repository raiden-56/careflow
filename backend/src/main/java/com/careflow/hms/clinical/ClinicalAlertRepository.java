package com.careflow.hms.clinical;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ClinicalAlertRepository extends JpaRepository<ClinicalAlert, UUID> {

    List<ClinicalAlert> findByAdmissionIdOrderByRaisedAtDesc(UUID admissionId);

    List<ClinicalAlert> findByAdmissionIdInAndStatusInOrderByRaisedAtDesc(Collection<UUID> admissionIds,
                                                                          Collection<ClinicalAlert.Status> statuses);

    List<ClinicalAlert> findByAdmissionIdInOrderByRaisedAtDesc(Collection<UUID> admissionIds);

    List<ClinicalAlert> findByOrganizationIdAndStatusIn(UUID organizationId, Collection<ClinicalAlert.Status> statuses);

    long countByAdmissionIdAndStatusIn(UUID admissionId, Collection<ClinicalAlert.Status> statuses);

    List<ClinicalAlert> findByAdmissionIdAndAlertTypeAndParameterAndStatusIn(UUID admissionId, ClinicalAlert.AlertType type,
                                                                             String parameter,
                                                                             Collection<ClinicalAlert.Status> statuses);

    List<ClinicalAlert> findBySourceTypeAndSourceIdAndStatusIn(String sourceType, UUID sourceId,
                                                               Collection<ClinicalAlert.Status> statuses);

    /** Critical alerts still OPEN (unacknowledged) - input for the ALERT_UNACKNOWLEDGED escalation job. */
    @Query("""
            select a from ClinicalAlert a
            where a.organizationId = :orgId and a.status = 'OPEN' and a.severity = 'CRITICAL'
              and coalesce(a.lastEscalatedAt, a.raisedAt) < :cutoff
            """)
    List<ClinicalAlert> findUnacknowledgedCritical(@Param("orgId") UUID orgId, @Param("cutoff") Instant cutoff);

    List<ClinicalAlert> findByAdmissionIdInAndRaisedAtAfterOrderByRaisedAtDesc(Collection<UUID> admissionIds, Instant after);
}
