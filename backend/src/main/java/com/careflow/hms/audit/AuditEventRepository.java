package com.careflow.hms.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    @Query("""
            select e from AuditEvent e
            where (:orgId is null or e.organizationId = :orgId)
              and (:actorId is null or e.actorUserId = :actorId)
              and (:patientId is null or e.patientId = :patientId)
              and (:action is null or e.action = :action)
              and (:outcome is null or e.outcome = :outcome)
              and e.occurredAt >= :from and e.occurredAt < :to
            """)
    Page<AuditEvent> search(@Param("orgId") UUID orgId, @Param("actorId") UUID actorId,
                            @Param("patientId") UUID patientId, @Param("action") String action,
                            @Param("outcome") AuditEvent.Outcome outcome,
                            @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    /** Hospital audit search (organization-scoped) with actor-name and entity filters. */
    @Query("""
            select e from AuditEvent e
            where e.organizationId = :orgId
              and (:actorId is null or e.actorUserId = :actorId)
              and (:actorName is null or lower(e.actorName) like :actorName)
              and (:patientId is null or e.patientId = :patientId)
              and (:action is null or e.action = :action)
              and (:entityType is null or e.entityType = :entityType)
              and (:outcome is null or e.outcome = :outcome)
              and e.occurredAt >= :from and e.occurredAt < :to
            """)
    Page<AuditEvent> searchOrganization(@Param("orgId") UUID orgId, @Param("actorId") UUID actorId,
                                        @Param("actorName") String actorName, @Param("patientId") UUID patientId,
                                        @Param("action") String action, @Param("entityType") String entityType,
                                        @Param("outcome") AuditEvent.Outcome outcome,
                                        @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    @Query("select distinct e.action from AuditEvent e where e.organizationId = :orgId order by e.action")
    java.util.List<String> distinctActions(@Param("orgId") UUID orgId);

    @Query("select e from AuditEvent e where e.organizationId = :orgId and e.entityType = :entityType and e.entityId = :entityId order by e.occurredAt")
    java.util.List<AuditEvent> findEntityHistory(@Param("orgId") UUID orgId, @Param("entityType") String entityType,
                                                 @Param("entityId") UUID entityId);

    /** Staff access to a patient's records - shown to the patient for access transparency. */
    @Query("""
            select e from AuditEvent e
            where e.patientId = :patientId and e.outcome = 'SUCCESS'
              and e.actorRole not in ('PATIENT')
            """)
    Page<AuditEvent> findAccessForPatient(@Param("patientId") UUID patientId, Pageable pageable);
}
