package com.careflow.hms.patient;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {

    Optional<Patient> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Patient> findByUserId(UUID userId);

    List<Patient> findByIdIn(Collection<UUID> ids);

    @Query("""
            select p from Patient p
            where p.organizationId = :orgId
              and (:status is null or p.status = :status)
              and (:q is null
                   or lower(concat(p.firstName, ' ', p.lastName)) like :q
                   or lower(p.mrn) like :q
                   or p.phone like :q)
            """)
    Page<Patient> search(@Param("orgId") UUID orgId, @Param("status") Patient.Status status,
                         @Param("q") String q, Pageable pageable);

    long countByOrganizationId(UUID organizationId);
}
