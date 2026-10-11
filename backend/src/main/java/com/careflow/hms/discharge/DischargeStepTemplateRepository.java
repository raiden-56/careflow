package com.careflow.hms.discharge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DischargeStepTemplateRepository extends JpaRepository<DischargeStepTemplate, UUID> {

    List<DischargeStepTemplate> findByOrganizationIdAndActiveTrueOrderByStepOrder(UUID organizationId);

    List<DischargeStepTemplate> findByOrganizationIdOrderByStepOrder(UUID organizationId);

    Optional<DischargeStepTemplate> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);
}
