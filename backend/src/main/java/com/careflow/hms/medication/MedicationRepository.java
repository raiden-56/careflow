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

    /** Catalogue search with optional filters (null = no filter). */
    @Query("""
            select m from Medication m
            where m.organizationId = :orgId
              and (:active is null or m.active = :active)
              and (:form is null or m.form = :form)
              and (:drugClass is null or lower(m.drugClass) = :drugClass)
              and (:highAlert is null or m.isHighAlert = :highAlert)
              and (:controlled is null or m.isControlled = :controlled)
              and (:q is null or lower(m.genericName) like :q or lower(m.brandName) like :q or lower(m.code) like :q
                   or lower(m.drugClass) like :q)
            """)
    Page<Medication> searchCatalogue(@Param("orgId") UUID orgId, @Param("active") Boolean active,
                                     @Param("form") Medication.Form form, @Param("drugClass") String drugClass,
                                     @Param("highAlert") Boolean highAlert, @Param("controlled") Boolean controlled,
                                     @Param("q") String q, Pageable pageable);

    @Query("""
            select m from Medication m
            where m.organizationId = :orgId
              and (:active is null or m.active = :active)
              and (:form is null or m.form = :form)
              and (:drugClass is null or lower(m.drugClass) = :drugClass)
              and (:highAlert is null or m.isHighAlert = :highAlert)
              and (:controlled is null or m.isControlled = :controlled)
              and (:q is null or lower(m.genericName) like :q or lower(m.brandName) like :q or lower(m.code) like :q
                   or lower(m.drugClass) like :q)
            """)
    List<Medication> searchCatalogueAll(@Param("orgId") UUID orgId, @Param("active") Boolean active,
                                        @Param("form") Medication.Form form, @Param("drugClass") String drugClass,
                                        @Param("highAlert") Boolean highAlert, @Param("controlled") Boolean controlled,
                                        @Param("q") String q, org.springframework.data.domain.Sort sort);

    List<Medication> findByOrganizationIdOrderByGenericNameAsc(UUID organizationId);

    @Query("select distinct m.drugClass from Medication m where m.organizationId = :orgId and m.drugClass is not null order by m.drugClass")
    List<String> drugClasses(@Param("orgId") UUID orgId);

    Optional<Medication> findByOrganizationIdAndCode(UUID organizationId, String code);
}
