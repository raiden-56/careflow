package com.careflow.hms.pharmacy;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    List<StockMovement> findByBatchIdOrderByPerformedAtDesc(UUID batchId);

    Page<StockMovement> findByOrganizationId(UUID organizationId, Pageable pageable);
}
