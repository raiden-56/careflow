package com.careflow.hms.pharmacy;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    List<StockMovement> findByBatchIdOrderByPerformedAtDesc(UUID batchId);

    Page<StockMovement> findByOrganizationId(UUID organizationId, Pageable pageable);

    List<StockMovement> findByReferenceTypeAndReferenceIdOrderByPerformedAtAsc(String referenceType, UUID referenceId);

    List<StockMovement> findByReferenceTypeAndReferenceIdIn(String referenceType, Collection<UUID> referenceIds);

    @Query("""
            select m from StockMovement m
            where m.organizationId = :orgId
              and (:batchId is null or m.batchId = :batchId)
              and (:medicationId is null or m.medicationId = :medicationId)
              and m.movementType in :types
              and m.performedAt >= :from
              and m.performedAt < :to
            """)
    Page<StockMovement> search(@Param("orgId") UUID orgId, @Param("batchId") UUID batchId,
                               @Param("medicationId") UUID medicationId,
                               @Param("types") Collection<StockMovement.MovementType> types,
                               @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}
