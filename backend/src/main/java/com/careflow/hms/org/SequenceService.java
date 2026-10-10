package com.careflow.hms.org;

import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Year;
import java.util.UUID;

/**
 * Generates human-readable identifiers (MRN, admission number, invoice number...) using an atomic
 * per-organization counter. Safe under concurrency (single UPSERT ... RETURNING statement).
 */
@Service
@RequiredArgsConstructor
public class SequenceService {

    private final JdbcTemplate jdbc;

    public long next(UUID organizationId, String sequenceName) {
        Long value = jdbc.queryForObject("""
                INSERT INTO organization_sequences (organization_id, sequence_name, next_value)
                VALUES (?, ?, 2)
                ON CONFLICT (organization_id, sequence_name)
                DO UPDATE SET next_value = organization_sequences.next_value + 1
                RETURNING next_value - 1
                """, Long.class, organizationId, sequenceName);
        return value == null ? 1 : value;
    }

    public String mrn() {
        UUID org = TenantContext.requireOrganizationId();
        return "MRN" + String.format("%07d", next(org, "MRN"));
    }

    /** Year-scoped identifier, e.g. ADM-2026-00012. */
    public String yearly(String prefix) {
        UUID org = TenantContext.requireOrganizationId();
        int year = Year.now().getValue();
        long n = next(org, prefix + "-" + year);
        return prefix + "-" + year + "-" + String.format("%05d", n);
    }

    public String admissionNumber() {
        return yearly("ADM");
    }

    public String invoiceNumber() {
        return yearly("INV");
    }

    public String receiptNumber() {
        return yearly("RCP");
    }

    public String adjustmentNumber() {
        return yearly("ADJ");
    }

    public String claimNumber() {
        return yearly("CLM");
    }

    public String labOrderNumber() {
        return yearly("LAB");
    }

    public String incidentNumber() {
        return yearly("INC");
    }
}
