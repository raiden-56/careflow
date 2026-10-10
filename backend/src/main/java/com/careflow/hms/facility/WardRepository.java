package com.careflow.hms.facility;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WardRepository extends JpaRepository<Ward, UUID> {

    Optional<Ward> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Ward> findByOrganizationIdOrderByName(UUID organizationId);

    List<Ward> findByOrganizationIdAndIdIn(UUID organizationId, Collection<UUID> ids);

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);
}
