package com.careflow.hms.billing;

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

/** A patient's billing query or dispute. */
@Getter
@Setter
@Entity
@Table(name = "billing_requests")
public class BillingRequest extends TenantEntity {

    public enum RequestType { DISPUTE, QUERY, ITEMIZATION, PAYMENT_PLAN }

    public enum Status { OPEN, IN_REVIEW, RESOLVED, REJECTED }

    @Column(nullable = false)
    private UUID invoiceId;

    @Column(nullable = false)
    private UUID patientId;

    private UUID lineItemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestType requestType;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    @Column(nullable = false)
    private UUID raisedBy;

    @Column(nullable = false)
    private Instant raisedAt = Instant.now();

    private String response;
    private UUID respondedBy;
    private Instant respondedAt;

    @Version
    private Long version;
}
