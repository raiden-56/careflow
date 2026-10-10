package com.careflow.hms.billing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceTariffRepository extends JpaRepository<ServiceTariff, UUID> {

    Optional<ServiceTariff> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<ServiceTariff> findByOrganizationIdAndCode(UUID organizationId, String code);

    Optional<ServiceTariff> findFirstByOrganizationIdAndCategoryAndRoomTypeAndActiveTrue(UUID organizationId,
                                                                                         ServiceTariff.Category category,
                                                                                         String roomType);

    List<ServiceTariff> findByOrganizationIdAndCategoryAndActiveTrueOrderByName(UUID organizationId, ServiceTariff.Category category);

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);

    @Query("""
            select t from ServiceTariff t
            where t.organizationId = :orgId
              and (:category is null or t.category = :category)
              and (:activeOnly = false or t.active = true)
              and (:q is null or lower(t.name) like :q or lower(t.code) like :q)
            """)
    Page<ServiceTariff> search(@Param("orgId") UUID orgId, @Param("category") ServiceTariff.Category category,
                               @Param("activeOnly") boolean activeOnly, @Param("q") String q, Pageable pageable);
}
