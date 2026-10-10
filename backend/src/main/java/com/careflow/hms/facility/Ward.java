package com.careflow.hms.facility;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "wards")
public class Ward extends TenantEntity {

    public enum WardType { GENERAL, ICU, HDU, PRIVATE, PAEDIATRIC, MATERNITY, ISOLATION, DAYCARE }

    public enum GenderPolicy { MIXED, MALE, FEMALE }

    private UUID departmentId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WardType wardType;

    private String floor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GenderPolicy genderPolicy = GenderPolicy.MIXED;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;
}
