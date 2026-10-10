package com.careflow.hms.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ThresholdRuleRepository extends JpaRepository<ThresholdRule, UUID> {

    List<ThresholdRule> findByOrganizationIdAndActiveTrue(UUID organizationId);

    List<ThresholdRule> findByOrganizationIdOrderByParameter(UUID organizationId);
}
