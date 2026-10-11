package com.careflow.hms.discharge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DischargeSummaryAmendmentRepository extends JpaRepository<DischargeSummaryAmendment, UUID> {

    List<DischargeSummaryAmendment> findBySummaryIdOrderByAmendedAtDesc(UUID summaryId);
}
