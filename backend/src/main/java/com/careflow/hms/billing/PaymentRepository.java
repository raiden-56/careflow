package com.careflow.hms.billing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Payment> findByOrganizationIdAndIdempotencyKey(UUID organizationId, String idempotencyKey);

    List<Payment> findByInvoiceIdOrderByReceivedAtAsc(UUID invoiceId);

    List<Payment> findByPatientIdInOrderByReceivedAtDesc(Collection<UUID> patientIds);

    @Query("select p from Payment p where p.organizationId = :orgId and p.receivedAt >= :from and p.receivedAt < :to")
    Page<Payment> findInRange(@Param("orgId") UUID orgId, @Param("from") Instant from, @Param("to") Instant to,
                              Pageable pageable);

    @Query("""
            select p from Payment p, Patient pt, Invoice i
            where pt.id = p.patientId and i.id = p.invoiceId and p.organizationId = :orgId
              and p.receivedAt >= :from and p.receivedAt < :to
              and p.method in :methods and p.status in :statuses
              and (:q is null or lower(p.receiptNumber) like :q or lower(i.invoiceNumber) like :q
                   or lower(pt.mrn) like :q or lower(concat(pt.firstName, ' ', pt.lastName)) like :q
                   or lower(coalesce(p.payerName, '')) like :q)
            """)
    Page<Payment> search(@Param("orgId") UUID orgId, @Param("from") Instant from, @Param("to") Instant to,
                         @Param("methods") Collection<Payment.Method> methods,
                         @Param("statuses") Collection<Payment.Status> statuses,
                         @Param("q") String q, Pageable pageable);
}
