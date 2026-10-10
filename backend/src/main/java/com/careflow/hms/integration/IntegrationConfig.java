package com.careflow.hms.integration;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Configuration of an integration boundary. Secrets are NEVER stored here: settings may only
 * reference secret names resolved from the environment/secret manager at runtime.
 */
@Getter
@Setter
@Entity
@Table(name = "integration_configs")
public class IntegrationConfig extends TenantEntity {

    public enum IntegrationType { ABDM, FHIR, LIS, PAYMENT_GATEWAY, SMS, EMAIL, INSURANCE_TPA }

    public enum Mode { DISABLED, DEV_ADAPTER, SANDBOX, LIVE }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IntegrationType integrationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Mode mode = Mode.DEV_ADAPTER;

    private String endpointUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> settings = new HashMap<>();

    private UUID updatedBy;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private Long version;
}
