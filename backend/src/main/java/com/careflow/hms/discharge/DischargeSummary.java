package com.careflow.hms.discharge;

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
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "discharge_summaries")
public class DischargeSummary extends TenantEntity {

    public enum Status { DRAFT, FINAL, AMENDED }

    @Column(nullable = false)
    private UUID admissionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    private String presentingComplaint;
    private String diagnosesText;
    private String treatmentSummary;
    private String proceduresText;
    private String relevantResults;
    private String conditionAtDischarge;
    private String dischargeMedications;
    private String dischargeInstructions;
    private String followUpPlan;
    private String warningSigns;

    @Column(nullable = false)
    private UUID preparedBy;

    @Column(nullable = false)
    private Instant preparedAt = Instant.now();

    private UUID finalizedBy;
    private Instant finalizedAt;

    @Column(nullable = false)
    private boolean releasedToPatient;

    private UUID releasedBy;
    private Instant releasedAt;
    private UUID updatedBy;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private Long version;
}
