package com.careflow.hms.notification;

import com.careflow.hms.common.TenantEntity;
import com.careflow.hms.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Configurable escalation: when an event stays unresolved for thresholdMinutes, notify a role. */
@Getter
@Setter
@Entity
@Table(name = "escalation_rules")
public class EscalationRule extends TenantEntity {

    public enum EventType {
        MEDICATION_OVERDUE, CRITICAL_OBSERVATION, ALERT_UNACKNOWLEDGED, DISCHARGE_BLOCKED,
        HANDOVER_UNACKNOWLEDGED, LAB_RESULT_UNREVIEWED
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType eventType;

    @Column(nullable = false)
    private int thresholdMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role escalateToRole;

    @Column(nullable = false)
    private int escalationLevel = 1;

    @Column(nullable = false)
    private String priority = "HIGH";

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private Long version;
}
