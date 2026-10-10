package com.careflow.hms.pharmacy;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DispensationRepository extends JpaRepository<Dispensation, UUID> {

    List<Dispensation> findByOrderIdOrderByDispensedAtDesc(UUID orderId);

    List<Dispensation> findByOrderIdIn(Collection<UUID> orderIds);

    Page<Dispensation> findByOrganizationId(UUID organizationId, Pageable pageable);

    List<Dispensation> findByAdmissionIdOrderByDispensedAtDesc(UUID admissionId);

    Optional<Dispensation> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dispensation d where d.id = :id and d.organizationId = :orgId")
    Optional<Dispensation> lockById(@Param("id") UUID id, @Param("orgId") UUID orgId);

    @Query(value = """
            select d from Dispensation d, Patient p, Medication m
            where p.id = d.patientId and m.id = d.medicationId
              and d.organizationId = :orgId
              and d.dispensedAt >= :from
              and d.dispensedAt < :to
              and (:patientId is null or d.patientId = :patientId)
              and (:medicationId is null or d.medicationId = :medicationId)
              and (:admissionId is null or d.admissionId = :admissionId)
              and d.status in :statuses
              and (:q is null
                   or lower(concat(p.firstName, ' ', p.lastName)) like :q
                   or lower(p.mrn) like :q
                   or lower(m.genericName) like :q
                   or lower(m.code) like :q)
            """,
            countQuery = """
            select count(d) from Dispensation d, Patient p, Medication m
            where p.id = d.patientId and m.id = d.medicationId
              and d.organizationId = :orgId
              and d.dispensedAt >= :from
              and d.dispensedAt < :to
              and (:patientId is null or d.patientId = :patientId)
              and (:medicationId is null or d.medicationId = :medicationId)
              and (:admissionId is null or d.admissionId = :admissionId)
              and d.status in :statuses
              and (:q is null
                   or lower(concat(p.firstName, ' ', p.lastName)) like :q
                   or lower(p.mrn) like :q
                   or lower(m.genericName) like :q
                   or lower(m.code) like :q)
            """)
    Page<Dispensation> search(@Param("orgId") UUID orgId, @Param("from") Instant from, @Param("to") Instant to,
                              @Param("patientId") UUID patientId, @Param("medicationId") UUID medicationId,
                              @Param("admissionId") UUID admissionId,
                              @Param("statuses") Collection<Dispensation.Status> statuses,
                              @Param("q") String q, Pageable pageable);

    List<Dispensation> findByOrganizationIdAndDispensedAtGreaterThanEqual(UUID organizationId, Instant from);
}
