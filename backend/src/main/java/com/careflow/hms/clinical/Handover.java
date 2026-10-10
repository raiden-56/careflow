package com.careflow.hms.clinical;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "handovers")
public class Handover extends TenantEntity {

    public enum Shift { MORNING, EVENING, NIGHT }

    public enum Status { DRAFT, SUBMITTED, ACKNOWLEDGED }

    @Column(nullable = false)
    private UUID wardId;

    @Column(nullable = false)
    private LocalDate shiftDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Shift shift;

    @Column(nullable = false)
    private UUID fromUserId;

    private UUID toUserId;
    private String generalNotes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(nullable = false)
    private boolean requiresAck = true;

    private Instant submittedAt;
    private UUID acknowledgedBy;
    private Instant acknowledgedAt;
    private String ackComment;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Long version;
}
