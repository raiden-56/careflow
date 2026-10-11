package com.careflow.hms.incident;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface IncidentReportRepository extends JpaRepository<IncidentReport, UUID> {

    Optional<IncidentReport> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<IncidentReport> findByOrganizationId(UUID organizationId, Pageable pageable);

    Page<IncidentReport> findByOrganizationIdAndStatus(UUID organizationId, IncidentReport.Status status, Pageable pageable);

    Page<IncidentReport> findByOrganizationIdAndReportedBy(UUID organizationId, UUID reportedBy, Pageable pageable);

    @Query("""
            select i from IncidentReport i
            where i.organizationId = :orgId
              and (:reportedBy is null or i.reportedBy = :reportedBy)
              and i.status in :statuses
              and (:severity is null or i.severity = :severity)
              and (:type is null or i.incidentType = :type)
              and (:reviewerId is null or i.reviewerId = :reviewerId)
              and (:q is null or lower(i.incidentNumber) like :q or lower(i.description) like :q or lower(i.location) like :q)
            """)
    Page<IncidentReport> search(@Param("orgId") UUID orgId, @Param("reportedBy") UUID reportedBy,
                                @Param("statuses") Collection<IncidentReport.Status> statuses,
                                @Param("severity") IncidentReport.Severity severity,
                                @Param("type") IncidentReport.IncidentType type,
                                @Param("reviewerId") UUID reviewerId,
                                @Param("q") String q, Pageable pageable);
}
