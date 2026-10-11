package com.careflow.hms.discharge;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Append-only record of an amendment to a finalized discharge summary (keeps the previous content). */
@Getter
@Setter
@Entity
@Table(name = "discharge_summary_amendments")
public class DischargeSummaryAmendment extends TenantEntity {

    @Column(nullable = false)
    private UUID summaryId;

    @Column(nullable = false)
    private String reason;

    /** JSON snapshot of the section texts before the amendment. */
    @Column(nullable = false)
    private String previousContent;

    @Column(nullable = false)
    private UUID amendedBy;

    @Column(nullable = false)
    private Instant amendedAt = Instant.now();
}
