package com.careflow.hms.billing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InsuranceClaimRepository extends JpaRepository<InsuranceClaim, UUID> {

    Optional<InsuranceClaim> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<InsuranceClaim> findByInvoiceIdOrderByCreatedAtAsc(UUID invoiceId);

    Page<InsuranceClaim> findByOrganizationId(UUID organizationId, Pageable pageable);

    Page<InsuranceClaim> findByOrganizationIdAndStatus(UUID organizationId, InsuranceClaim.Status status, Pageable pageable);
}
