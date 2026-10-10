package com.careflow.hms.pharmacy;

import jakarta.persistence.LockModeType;
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
}
