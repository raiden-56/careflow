package com.careflow.hms.common;

import com.careflow.hms.security.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Base class for every organization-scoped row. The organization is stamped from the
 * current security context on insert and can never be changed afterwards.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @PrePersist
    protected void stampTenant() {
        if (organizationId == null) {
            organizationId = TenantContext.requireOrganizationId();
        }
    }
}
