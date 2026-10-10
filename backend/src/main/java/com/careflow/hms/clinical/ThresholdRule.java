package com.careflow.hms.clinical;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/** Hospital-configured observation threshold. Decision support only; never a diagnosis. */
@Getter
@Setter
@Entity
@Table(name = "threshold_rules")
public class ThresholdRule extends TenantEntity {

    public enum Parameter { TEMPERATURE_C, PULSE_BPM, RESPIRATORY_RATE, SYSTOLIC_BP, DIASTOLIC_BP, SPO2, PAIN_SCORE, BLOOD_GLUCOSE }

    public enum Severity { WARNING, CRITICAL }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Parameter parameter;

    private BigDecimal minValue;
    private BigDecimal maxValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    private Role notifyRole;

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

    /** Human-readable threshold, e.g. "SpO2 < 92" or "Pulse > 120". */
    public String describeBounds() {
        StringBuilder sb = new StringBuilder();
        if (minValue != null) {
            sb.append("< ").append(minValue.stripTrailingZeros().toPlainString());
        }
        if (maxValue != null) {
            if (!sb.isEmpty()) {
                sb.append(" or ");
            }
            sb.append("> ").append(maxValue.stripTrailingZeros().toPlainString());
        }
        return parameter + " " + sb;
    }
}
