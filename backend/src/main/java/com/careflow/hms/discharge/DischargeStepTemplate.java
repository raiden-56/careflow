package com.careflow.hms.discharge;

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

/** Hospital-configured discharge checklist step. */
@Getter
@Setter
@Entity
@Table(name = "discharge_step_templates")
public class DischargeStepTemplate extends TenantEntity {

    public enum Category { CLINICAL, NURSING, PHARMACY, ADMINISTRATIVE, FINANCIAL, PATIENT }

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private int stepOrder;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role responsibleRole;

    @Column(nullable = false)
    private boolean required = true;

    /** Optional automatic blocker check key (see DischargeBlockerService), e.g. PENDING_RESULTS. */
    private String autoCheck;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private Long version;
}
