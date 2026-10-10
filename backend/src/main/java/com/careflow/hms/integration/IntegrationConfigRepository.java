package com.careflow.hms.integration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IntegrationConfigRepository extends JpaRepository<IntegrationConfig, UUID> {

    List<IntegrationConfig> findByOrganizationIdOrderByIntegrationType(UUID organizationId);

    Optional<IntegrationConfig> findByOrganizationIdAndIntegrationType(UUID organizationId, IntegrationConfig.IntegrationType type);
}
