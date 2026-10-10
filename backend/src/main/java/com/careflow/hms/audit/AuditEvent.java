package com.careflow.hms.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Append-only audit record (enforced by a database trigger). */
@Getter
@Setter
@Entity
@Immutable
@Table(name = "audit_events")
public class AuditEvent {

    public enum Outcome { SUCCESS, DENIED, FAILURE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;
    private UUID actorUserId;
    private String actorName;
    private String actorRole;

    @Column(nullable = false)
    private String action;

    private String entityType;
    private UUID entityId;
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome = Outcome.SUCCESS;

    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> details;

    private String ipAddress;
    private String traceId;

    @Column(nullable = false)
    private Instant occurredAt = Instant.now();
}
