package com.careflow.hms.discharge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FollowUpRepository extends JpaRepository<FollowUp, UUID> {

    List<FollowUp> findByAdmissionIdOrderByScheduledAtAsc(UUID admissionId);

    List<FollowUp> findByPatientIdOrderByScheduledAtAsc(UUID patientId);

    List<FollowUp> findByPatientIdInOrderByScheduledAtAsc(Collection<UUID> patientIds);

    /** Scheduled follow-ups starting within the reminder window that have not yet had a reminder. */
    @Query("""
            select f from FollowUp f
            where f.organizationId = :orgId and f.status in ('PLANNED','SCHEDULED')
              and f.scheduledAt between :from and :to and f.reminderSentAt is null
            """)
    List<FollowUp> findDueForReminder(@Param("orgId") UUID orgId, @Param("from") Instant from, @Param("to") Instant to);

    List<FollowUp> findByOrganizationIdAndStatusInOrderByScheduledAtAsc(UUID organizationId, Collection<FollowUp.Status> statuses);
}
