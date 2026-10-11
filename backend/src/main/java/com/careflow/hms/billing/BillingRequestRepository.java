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

public interface BillingRequestRepository extends JpaRepository<BillingRequest, UUID> {

    List<BillingRequest> findByInvoiceIdOrderByRaisedAtDesc(UUID invoiceId);

    List<BillingRequest> findByPatientIdOrderByRaisedAtDesc(UUID patientId);

    Page<BillingRequest> findByOrganizationId(UUID organizationId, Pageable pageable);

    Optional<BillingRequest> findByIdAndOrganizationId(UUID id, UUID organizationId);

    long countByOrganizationIdAndStatusIn(UUID organizationId, Collection<BillingRequest.Status> statuses);

    @Query("""
            select r from BillingRequest r, Patient p, Invoice i
            where p.id = r.patientId and i.id = r.invoiceId and r.organizationId = :orgId
              and r.status in :statuses
              and (:type is null or r.requestType = :type)
              and (:q is null or lower(r.subject) like :q or lower(p.mrn) like :q
                   or lower(concat(p.firstName, ' ', p.lastName)) like :q or lower(i.invoiceNumber) like :q)
            """)
    Page<BillingRequest> search(@Param("orgId") UUID orgId, @Param("statuses") Collection<BillingRequest.Status> statuses,
                                @Param("type") BillingRequest.RequestType type, @Param("q") String q, Pageable pageable);
}
