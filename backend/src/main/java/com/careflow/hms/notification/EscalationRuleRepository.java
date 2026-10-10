package com.careflow.hms.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EscalationRuleRepository extends JpaRepository<EscalationRule, UUID> {

    List<EscalationRule> findByOrganizationIdAndEventTypeAndActiveTrueOrderByEscalationLevel(UUID organizationId,
                                                                                            EscalationRule.EventType eventType);

    List<EscalationRule> findByOrganizationIdOrderByEventTypeAscEscalationLevelAsc(UUID organizationId);
}
