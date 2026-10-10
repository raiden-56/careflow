package com.careflow.hms.medication;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MedicationRepository extends JpaRepository<Medication, UUID> {

    Optional<Medication> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Medication> findByIdIn(Collection<UUID> ids);

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);

    @Query("""
            select m from Medication m
            where m.organizationId = :orgId
              and (:activeOnly = false or m.active = true)
              and (:q is null or lower(m.genericName) like :q or lower(m.brandName) like :q or lower(m.code) like :q)
            """)
    Page<Medication> search(@Param("orgId") UUID orgId, @Param("activeOnly") boolean activeOnly,
                            @Param("q") String q, Pageable pageable);
}
