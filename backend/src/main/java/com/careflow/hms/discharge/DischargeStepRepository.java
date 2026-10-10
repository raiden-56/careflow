package com.careflow.hms.discharge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DischargeStepRepository extends JpaRepository<DischargeStep, UUID> {

    List<DischargeStep> findByWorkflowIdOrderByStepOrder(UUID workflowId);

    List<DischargeStep> findByWorkflowIdIn(Collection<UUID> workflowIds);

    Optional<DischargeStep> findByWorkflowIdAndTemplateCode(UUID workflowId, String templateCode);
}
