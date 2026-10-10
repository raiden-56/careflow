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

@Getter
@Setter
@Entity
@Table(name = "vital_signs")
public class VitalSign extends TenantEntity {

    public enum Consciousness { ALERT, VOICE, PAIN, UNRESPONSIVE, CONFUSED }

    @Column(nullable = false)
    private UUID admissionId;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private Instant recordedAt;

    @Column(nullable = false)
    private UUID recordedBy;

    @Column(name = "temperature_c")
    private BigDecimal temperatureC;
    private Integer pulseBpm;
    private Integer respiratoryRate;
    private Integer systolicBp;
    private Integer diastolicBp;
    private Integer spo2;

    @Column(nullable = false)
    private boolean onOxygen;

    private Integer painScore;
    private BigDecimal bloodGlucose;
    private BigDecimal weightKg;

    @Enumerated(EnumType.STRING)
    private Consciousness consciousness;

    private String notes;

    @Column(nullable = false)
    private boolean enteredInError;

    private String errorReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Long version;
}
