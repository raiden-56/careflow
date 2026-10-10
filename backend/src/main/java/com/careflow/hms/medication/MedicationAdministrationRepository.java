package com.careflow.hms.medication;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MedicationAdministrationRepository extends JpaRepository<MedicationAdministration, UUID> {

    List<MedicationAdministration> findByAdmissionIdAndScheduledAtBetweenOrderByScheduledAtAsc(UUID admissionId, Instant from, Instant to);

    List<MedicationAdministration> findByAdmissionIdInAndScheduledAtBetweenOrderByScheduledAtAsc(Collection<UUID> admissionIds, Instant from, Instant to);

    List<MedicationAdministration> findByOrderIdOrderByScheduledAtAsc(UUID orderId);

    boolean existsByOrderIdAndScheduledAt(UUID orderId, Instant scheduledAt);

    /** SCHEDULED doses whose time has passed the grace cut-off and have not yet been flagged. */
    @Query("""
            select m from MedicationAdministration m
            where m.organizationId = :orgId and m.status = 'SCHEDULED'
              and m.scheduledAt < :cutoff and m.overdueFlaggedAt is null
            """)
    List<MedicationAdministration> findNewlyOverdue(@Param("orgId") UUID orgId, @Param("cutoff") Instant cutoff);

    @Query("""
            select m from MedicationAdministration m
            where m.organizationId = :orgId
              and (m.status in :statuses or (m.status = 'SCHEDULED' and m.overdueFlaggedAt is not null))
              and m.scheduledAt between :from and :to
            """)
    Page<MedicationAdministration> findExceptions(@Param("orgId") UUID orgId,
                                                  @Param("statuses") Collection<MedicationAdministration.Status> statuses,
                                                  @Param("from") Instant from, @Param("to") Instant to,
                                                  Pageable pageable);

    List<MedicationAdministration> findByAdmissionIdAndStatusAndScheduledAtAfter(UUID admissionId,
                                                                                  MedicationAdministration.Status status,
                                                                                  Instant after);
}
