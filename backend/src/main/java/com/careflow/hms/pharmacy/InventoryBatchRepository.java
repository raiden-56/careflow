package com.careflow.hms.pharmacy;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryBatchRepository extends JpaRepository<InventoryBatch, UUID> {

    Optional<InventoryBatch> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from InventoryBatch b where b.id = :id and b.organizationId = :orgId")
    Optional<InventoryBatch> lockById(@Param("id") UUID id, @Param("orgId") UUID orgId);

    List<InventoryBatch> findByMedicationIdOrderByExpiryDateAsc(UUID medicationId);

    List<InventoryBatch> findByMedicationIdInAndStatus(Collection<UUID> medicationIds, InventoryBatch.Status status);

    List<InventoryBatch> findByOrganizationIdAndStatusAndExpiryDateLessThanEqual(UUID organizationId, InventoryBatch.Status status, LocalDate date);

    List<InventoryBatch> findByOrganizationIdOrderByExpiryDateAsc(UUID organizationId);

    @Query("select coalesce(sum(b.quantityOnHand), 0) from InventoryBatch b where b.medicationId = :medId and b.status = 'ACTIVE' and b.expiryDate >= :today")
    long usableStock(@Param("medId") UUID medicationId, @Param("today") LocalDate today);

    /** [medicationId, usable quantity] for the given medications (ACTIVE, not expired). */
    @Query("""
            select b.medicationId, coalesce(sum(b.quantityOnHand), 0) from InventoryBatch b
            where b.medicationId in :medIds and b.status = 'ACTIVE' and b.expiryDate >= :today
            group by b.medicationId
            """)
    List<Object[]> usableStockByMedication(@Param("medIds") Collection<UUID> medicationIds, @Param("today") LocalDate today);

    /** Usable batches in FEFO order (earliest expiry first). */
    @Query("""
            select b from InventoryBatch b
            where b.medicationId = :medId and b.status = 'ACTIVE' and b.expiryDate >= :today and b.quantityOnHand > 0
            order by b.expiryDate asc, b.receivedAt asc
            """)
    List<InventoryBatch> findUsableFefo(@Param("medId") UUID medicationId, @Param("today") LocalDate today);

    List<InventoryBatch> findByOrganizationIdAndQuantityOnHandGreaterThan(UUID organizationId, int quantity);

    boolean existsByOrganizationIdAndMedicationIdAndBatchNumberIgnoreCase(UUID organizationId, UUID medicationId, String batchNumber);

    @Query(value = """
            select b from InventoryBatch b, Medication m
            where m.id = b.medicationId and b.organizationId = :orgId
              and (:medicationId is null or b.medicationId = :medicationId)
              and b.status in :statuses
              and (:expiringBy is null or b.expiryDate <= :expiringBy)
              and (:includeEmpty = true or b.quantityOnHand > 0)
              and (:q is null or lower(b.batchNumber) like :q or lower(m.genericName) like :q
                   or lower(m.code) like :q or lower(b.supplier) like :q)
            """,
            countQuery = """
            select count(b) from InventoryBatch b, Medication m
            where m.id = b.medicationId and b.organizationId = :orgId
              and (:medicationId is null or b.medicationId = :medicationId)
              and b.status in :statuses
              and (:expiringBy is null or b.expiryDate <= :expiringBy)
              and (:includeEmpty = true or b.quantityOnHand > 0)
              and (:q is null or lower(b.batchNumber) like :q or lower(m.genericName) like :q
                   or lower(m.code) like :q or lower(b.supplier) like :q)
            """)
    Page<InventoryBatch> search(@Param("orgId") UUID orgId, @Param("medicationId") UUID medicationId,
                                @Param("statuses") Collection<InventoryBatch.Status> statuses,
                                @Param("expiringBy") LocalDate expiringBy, @Param("includeEmpty") boolean includeEmpty,
                                @Param("q") String q, org.springframework.data.domain.Pageable pageable);
}
