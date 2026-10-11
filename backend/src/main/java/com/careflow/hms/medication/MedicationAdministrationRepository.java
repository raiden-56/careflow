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

    /** SCHEDULED doses past the cut-off whose overdue escalation has not reached the given level yet. */
    @Query("""
            select m from MedicationAdministration m
            where m.organizationId = :orgId and m.status = 'SCHEDULED'
              and m.scheduledAt < :cutoff and m.overdueEscalationLevel < :maxLevel
            """)
    List<MedicationAdministration> findOverdueBelowLevel(@Param("orgId") UUID orgId, @Param("cutoff") Instant cutoff,
                                                         @Param("maxLevel") int maxLevel);

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

    /** Exception queue for a set of admissions: recorded exceptions plus overdue (flagged) doses. */
    @Query("""
            select m from MedicationAdministration m
            where m.admissionId in :ids
              and (m.status in ('DELAYED','REFUSED','MISSED','HELD')
                   or (m.status = 'SCHEDULED' and m.overdueFlaggedAt is not null))
              and m.followUpStatus in :followUps
            """)
    Page<MedicationAdministration> findExceptionQueue(@Param("ids") Collection<UUID> admissionIds,
                                                      @Param("followUps") Collection<MedicationAdministration.FollowUpStatus> followUps,
                                                      Pageable pageable);

    @Query("""
            select m from MedicationAdministration m
            where m.admissionId in :ids and m.status in :statuses and m.scheduledAt between :from and :to
            """)
    Page<MedicationAdministration> findHistory(@Param("ids") Collection<UUID> admissionIds,
                                               @Param("statuses") Collection<MedicationAdministration.Status> statuses,
                                               @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    List<MedicationAdministration> findByAdmissionIdAndStatusAndScheduledAtAfter(UUID admissionId,
                                                                                  MedicationAdministration.Status status,
                                                                                  Instant after);

    List<MedicationAdministration> findByOrderIdAndStatusAndScheduledAtAfter(UUID orderId, MedicationAdministration.Status status,
                                                                             Instant after);

    @Query("select m.scheduledAt from MedicationAdministration m where m.orderId = :orderId")
    List<Instant> scheduledTimes(@Param("orderId") UUID orderId);

    List<MedicationAdministration> findByAdmissionIdInAndStatusInAndScheduledAtBetweenOrderByScheduledAtAsc(
            Collection<UUID> admissionIds, Collection<MedicationAdministration.Status> statuses, Instant from, Instant to);

    /** Future SCHEDULED doses of orders that are no longer active (held/discontinued/completed). */
    @Query("""
            select m from MedicationAdministration m, MedicationOrder o
            where o.id = m.orderId and m.organizationId = :orgId and m.status = 'SCHEDULED'
              and m.scheduledAt > :after and o.status in ('ON_HOLD','DISCONTINUED','COMPLETED')
            """)
    List<MedicationAdministration> findScheduledForInactiveOrders(@Param("orgId") UUID orgId, @Param("after") Instant after);

    List<MedicationAdministration> findByOrderIdInAndStatusOrderByAdministeredAtDesc(Collection<UUID> orderIds,
                                                                                     MedicationAdministration.Status status);

    List<MedicationAdministration> findByOrderIdInAndStatusAndScheduledAtAfterOrderByScheduledAtAsc(Collection<UUID> orderIds,
                                                                                                    MedicationAdministration.Status status,
                                                                                                    Instant after);
}
