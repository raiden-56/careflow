package com.careflow.hms.incident;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IncidentReportRepository extends JpaRepository<IncidentReport, UUID> {

    Optional<IncidentReport> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<IncidentReport> findByOrganizationId(UUID organizationId, Pageable pageable);

    Page<IncidentReport> findByOrganizationIdAndStatus(UUID organizationId, IncidentReport.Status status, Pageable pageable);

    Page<IncidentReport> findByOrganizationIdAndReportedBy(UUID organizationId, UUID reportedBy, Pageable pageable);
}
