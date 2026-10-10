package com.careflow.hms.billing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinancialAdjustmentRepository extends JpaRepository<FinancialAdjustment, UUID> {

    Optional<FinancialAdjustment> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<FinancialAdjustment> findByInvoiceIdOrderByRequestedAtAsc(UUID invoiceId);

    Page<FinancialAdjustment> findByOrganizationIdAndStatus(UUID organizationId, FinancialAdjustment.Status status, Pageable pageable);

    Page<FinancialAdjustment> findByOrganizationId(UUID organizationId, Pageable pageable);
}
