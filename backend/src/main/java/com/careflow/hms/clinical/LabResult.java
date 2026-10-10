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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One result component of an investigation (e.g. "Haemoglobin" of a CBC). */
@Getter
@Setter
@Entity
@Table(name = "lab_results")
public class LabResult extends TenantEntity {

    public enum Flag {
        NORMAL, LOW, HIGH, CRITICAL_LOW, CRITICAL_HIGH, ABNORMAL;

        public boolean isCritical() {
            return this == CRITICAL_LOW || this == CRITICAL_HIGH;
        }
    }

    public enum Source { MANUAL, LIS_ADAPTER }

    public enum Status { PRELIMINARY, FINAL, CORRECTED, ENTERED_IN_ERROR }

    @Column(nullable = false)
    private UUID labOrderId;

    @Column(nullable = false)
    private UUID admissionId;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private String component;

    @Column(nullable = false)
    private String valueText;

    private BigDecimal valueNumeric;
    private String unit;
    private String referenceRange;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Flag flag = Flag.NORMAL;

    private String interpretation;

    @Column(nullable = false)
    private UUID resultedBy;

    @Column(nullable = false)
    private Instant resultedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source = Source.MANUAL;

    private UUID reviewedBy;
    private Instant reviewedAt;
    private String reviewComment;

    @Column(nullable = false)
    private boolean releasedToPatient;

    private Instant releasedAt;
    private UUID releasedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.FINAL;

    @Version
    private Long version;
}
