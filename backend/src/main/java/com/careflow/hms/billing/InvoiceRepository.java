package com.careflow.hms.billing;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id and i.organizationId = :orgId")
    Optional<Invoice> lockById(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Query("select i from Invoice i where i.admissionId = :admissionId and i.status <> 'CANCELLED'")
    Optional<Invoice> findActiveByAdmission(@Param("admissionId") UUID admissionId);

    List<Invoice> findByPatientIdOrderByCreatedAtDesc(UUID patientId);

    List<Invoice> findByPatientIdInOrderByCreatedAtDesc(Collection<UUID> patientIds);

    @Query("""
            select i from Invoice i, Patient p
            where p.id = i.patientId and i.organizationId = :orgId
              and i.status in :statuses
              and (:q is null or lower(i.invoiceNumber) like :q or lower(p.mrn) like :q
                   or lower(concat(p.firstName, ' ', p.lastName)) like :q)
            """)
    Page<Invoice> search(@Param("orgId") UUID orgId, @Param("statuses") Collection<Invoice.Status> statuses,
                         @Param("q") String q, Pageable pageable);

    List<Invoice> findByOrganizationIdAndStatusIn(UUID organizationId, Collection<Invoice.Status> statuses);
}
