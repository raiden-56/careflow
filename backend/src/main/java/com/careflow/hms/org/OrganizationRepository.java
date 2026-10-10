package com.careflow.hms.org;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findByCode(String code);

    boolean existsByCode(String code);

    List<Organization> findByPlatformFalseAndStatus(Organization.Status status);

    Optional<Organization> findFirstByPlatformTrue();
}
