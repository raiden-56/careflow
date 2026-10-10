package com.careflow.hms.timeline;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.UUID;

public interface TimelineEventRepository extends JpaRepository<TimelineEvent, UUID> {

    @Query("""
            select e from TimelineEvent e
            where e.admissionId = :admissionId
              and e.visibility in :visibilities
              and e.category in :categories
            """)
    Page<TimelineEvent> findForAdmission(@Param("admissionId") UUID admissionId,
                                         @Param("visibilities") Collection<TimelineEvent.Visibility> visibilities,
                                         @Param("categories") Collection<TimelineEvent.Category> categories,
                                         Pageable pageable);

    @Query("""
            select e from TimelineEvent e
            where e.patientId = :patientId
              and e.visibility in :visibilities
              and e.category in :categories
            """)
    Page<TimelineEvent> findForPatient(@Param("patientId") UUID patientId,
                                       @Param("visibilities") Collection<TimelineEvent.Visibility> visibilities,
                                       @Param("categories") Collection<TimelineEvent.Category> categories,
                                       Pageable pageable);
}
