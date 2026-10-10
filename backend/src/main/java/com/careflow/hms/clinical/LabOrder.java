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
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "lab_orders")
public class LabOrder extends TenantEntity {

    public enum Category { LAB, IMAGING, OTHER }

    public enum Priority { ROUTINE, URGENT, STAT }

    public enum Status {
        ORDERED, COLLECTED, IN_PROGRESS, RESULTED, REVIEWED, CANCELLED;

        private static final Map<Status, Set<Status>> ALLOWED = Map.of(
                ORDERED, EnumSet.of(COLLECTED, IN_PROGRESS, RESULTED, CANCELLED),
                COLLECTED, EnumSet.of(IN_PROGRESS, RESULTED, CANCELLED),
                IN_PROGRESS, EnumSet.of(RESULTED, CANCELLED),
                RESULTED, EnumSet.of(REVIEWED, RESULTED),
                REVIEWED, EnumSet.of(RESULTED),
                CANCELLED, EnumSet.noneOf(Status.class));

        public boolean canMoveTo(Status target) {
            return ALLOWED.get(this).contains(target);
        }

        /** Investigation still awaiting a result or clinician review. */
        public boolean isOutstanding() {
            return this != REVIEWED && this != CANCELLED;
        }
    }

    @Column(nullable = false)
    private UUID admissionId;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false, updatable = false)
    private String orderNumber;

    private UUID tariffId;

    @Column(nullable = false)
    private String testCode;

    @Column(nullable = false)
    private String testName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category = Category.LAB;

    private String specimen;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority = Priority.ROUTINE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ORDERED;

    private String clinicalInfo;

    @Column(nullable = false)
    private UUID orderedBy;

    @Column(nullable = false)
    private Instant orderedAt = Instant.now();

    private Instant dueAt;
    private UUID collectedBy;
    private Instant collectedAt;
    private UUID cancelledBy;
    private Instant cancelledAt;
    private String cancelReason;

    @Column(nullable = false)
    private boolean chargePosted;

    @Version
    private Long version;

    public void transitionTo(Status target) {
        if (!status.canMoveTo(target)) {
            throw com.careflow.hms.common.ApiException.rule("INVALID_LAB_TRANSITION",
                    "Investigation cannot move from " + status + " to " + target);
        }
        status = target;
    }
}
