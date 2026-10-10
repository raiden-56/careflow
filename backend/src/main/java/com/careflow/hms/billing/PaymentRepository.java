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
}
