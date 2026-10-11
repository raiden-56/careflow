package com.careflow.hms.billing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinancialAdjustmentRepository extends JpaRepository<FinancialAdjustment, UUID> {

    Optional<FinancialAdjustment> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<FinancialAdjustment> findByInvoiceIdOrderByRequestedAtAsc(UUID invoiceId);

    Page<FinancialAdjustment> findByOrganizationIdAndStatus(UUID organizationId, FinancialAdjustment.Status status, Pageable pageable);

    Page<FinancialAdjustment> findByOrganizationId(UUID organizationId, Pageable pageable);

    List<FinancialAdjustment> findByOrganizationIdAndStatus(UUID organizationId, FinancialAdjustment.Status status);

    @Query("""
            select a from FinancialAdjustment a, Invoice i, Patient p
            where i.id = a.invoiceId and p.id = i.patientId and a.organizationId = :orgId
              and a.status in :statuses and a.adjustmentType in :types
              and (:q is null or lower(a.adjustmentNumber) like :q or lower(i.invoiceNumber) like :q
                   or lower(p.mrn) like :q or lower(concat(p.firstName, ' ', p.lastName)) like :q)
            """)
    Page<FinancialAdjustment> search(@Param("orgId") UUID orgId,
                                     @Param("statuses") Collection<FinancialAdjustment.Status> statuses,
                                     @Param("types") Collection<FinancialAdjustment.Type> types,
                                     @Param("q") String q, Pageable pageable);
}
