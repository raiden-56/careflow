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

public interface InsuranceClaimRepository extends JpaRepository<InsuranceClaim, UUID> {

    Optional<InsuranceClaim> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<InsuranceClaim> findByInvoiceIdOrderByCreatedAtAsc(UUID invoiceId);

    Page<InsuranceClaim> findByOrganizationId(UUID organizationId, Pageable pageable);

    Page<InsuranceClaim> findByOrganizationIdAndStatus(UUID organizationId, InsuranceClaim.Status status, Pageable pageable);

    List<InsuranceClaim> findByOrganizationIdAndStatusIn(UUID organizationId, Collection<InsuranceClaim.Status> statuses);

    @Query("""
            select c from InsuranceClaim c, Invoice i, Patient p
            where i.id = c.invoiceId and p.id = c.patientId and c.organizationId = :orgId
              and c.status in :statuses
              and (:q is null or lower(c.claimNumber) like :q or lower(c.payerName) like :q
                   or lower(c.policyNumber) like :q or lower(i.invoiceNumber) like :q
                   or lower(p.mrn) like :q or lower(concat(p.firstName, ' ', p.lastName)) like :q)
            """)
    Page<InsuranceClaim> search(@Param("orgId") UUID orgId, @Param("statuses") Collection<InsuranceClaim.Status> statuses,
                                @Param("q") String q, Pageable pageable);
}
