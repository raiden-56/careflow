package com.careflow.hms.patient;

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
@Table(name = "allergies")
public class Allergy extends TenantEntity {

    public enum AllergyType { DRUG, FOOD, ENVIRONMENTAL, OTHER }

    public enum Severity { MILD, MODERATE, SEVERE, LIFE_THREATENING }

    public enum Status { ACTIVE, INACTIVE, ENTERED_IN_ERROR }

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private String allergen;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AllergyType allergyType = AllergyType.DRUG;

    private String reaction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    private UUID recordedBy;

    @Column(nullable = false)
    private Instant recordedAt = Instant.now();

    @Version
    private Long version;
}
