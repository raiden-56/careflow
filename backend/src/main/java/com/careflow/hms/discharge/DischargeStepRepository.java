package com.careflow.hms.discharge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DischargeStepRepository extends JpaRepository<DischargeStep, UUID> {

    List<DischargeStep> findByWorkflowIdOrderByStepOrder(UUID workflowId);

    List<DischargeStep> findByWorkflowIdIn(Collection<UUID> workflowIds);

    Optional<DischargeStep> findByWorkflowIdAndTemplateCode(UUID workflowId, String templateCode);

    Optional<DischargeStep> findByIdAndWorkflowId(UUID id, UUID workflowId);

    /** BLOCKED steps of open workflows (for escalation). */
    @Query("""
            select s from DischargeStep s, DischargeWorkflow w
            where w.id = s.workflowId and w.organizationId = :orgId
              and w.status in ('IN_PROGRESS','READY') and s.status = 'BLOCKED'
            """)
    List<DischargeStep> findBlockedInOpenWorkflows(@Param("orgId") UUID orgId);
}
