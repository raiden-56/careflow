package com.careflow.hms.billing;

import com.careflow.hms.billing.BillingDtos.AgingBucket;
import com.careflow.hms.billing.BillingDtos.InvoiceSummary;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.Money;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.org.SettingsService;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Invoice list queries whose balance and payment status are aggregated from the append-only ledger
 * inside the database, so filtering, sorting and paging by balance stay server-side and exact.
 * The payment-status CASE mirrors {@link LedgerService#summarize}.
 */
@Component
@RequiredArgsConstructor
public class InvoiceQueries {

    private static final Map<String, String> SORTS = Map.of(
            "invoiceNumber", "invoice_number",
            "createdAt", "created_at",
            "balance", "balance",
            "netCharges", "net_charges",
            "patientName", "patient_name",
            "dischargedAt", "discharged_at",
            "ageDays", "age_days",
            "status", "status");

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;

    @Builder
    public record Filter(UUID orgId, Collection<Invoice.Status> statuses,
                         Collection<LedgerService.PaymentStatus> paymentStatuses, Instant from, Instant to,
                         UUID departmentId, String q, Collection<String> admissionStatuses, boolean outstandingOnly,
                         UUID patientId, String bucket, Boolean overdueOnly) {
    }

    /** Row with aging information (reference date = discharge date, else invoice date). */
    public record Row(InvoiceSummary summary, LocalDate referenceDate, long ageDays) {
    }

    private static String baseSql() {
        return """
                WITH bal AS (
                    SELECT invoice_id,
                           SUM(amount) AS balance,
                           SUM(CASE WHEN entry_type IN ('CHARGE','CHARGE_REVERSAL') THEN amount ELSE 0 END) AS net_charges,
                           SUM(CASE WHEN entry_type = 'DEBIT_NOTE' THEN amount ELSE 0 END) AS debit_notes,
                           -SUM(CASE WHEN entry_type IN ('PAYMENT','PAYMENT_REVERSAL','DISCOUNT','WRITE_OFF','CREDIT_NOTE','INSURANCE_SETTLEMENT') THEN amount ELSE 0 END) AS credits,
                           -SUM(CASE WHEN entry_type IN ('PAYMENT','PAYMENT_REVERSAL') THEN amount ELSE 0 END) AS net_payments,
                           MAX(CASE WHEN entry_type = 'PAYMENT' THEN created_at END) AS last_payment_at
                    FROM ledger_entries WHERE organization_id = :orgId GROUP BY invoice_id
                ), v AS (
                    SELECT i.id, i.invoice_number, i.status, i.payer_type, i.created_at, i.finalized_at, i.patient_id,
                           p.first_name || ' ' || p.last_name AS patient_name, p.mrn,
                           i.admission_id, a.admission_number, a.status AS admission_status, a.admitted_at, a.discharged_at,
                           a.department_id, d.name AS department_name,
                           COALESCE(b.net_charges, 0) AS net_charges, COALESCE(b.debit_notes, 0) AS debit_notes,
                           COALESCE(b.credits, 0) AS credits, COALESCE(b.net_payments, 0) AS net_payments,
                           COALESCE(b.balance, 0) AS balance, b.last_payment_at,
                           CASE WHEN COALESCE(b.net_charges, 0) = 0 AND COALESCE(b.debit_notes, 0) = 0
                                     THEN (CASE WHEN COALESCE(b.balance, 0) < 0 THEN 'CREDIT_BALANCE' ELSE 'NO_CHARGES' END)
                                WHEN COALESCE(b.balance, 0) < 0 THEN 'CREDIT_BALANCE'
                                WHEN COALESCE(b.balance, 0) = 0 THEN 'PAID'
                                WHEN COALESCE(b.credits, 0) > 0 THEN 'PARTIALLY_PAID'
                                ELSE 'UNPAID' END AS payment_status,
                           COALESCE((a.discharged_at AT TIME ZONE CAST(:tz AS text))::date, (i.created_at AT TIME ZONE CAST(:tz AS text))::date) AS ref_date,
                           (CAST(:today AS date) - COALESCE((a.discharged_at AT TIME ZONE CAST(:tz AS text))::date, (i.created_at AT TIME ZONE CAST(:tz AS text))::date)) AS age_days
                    FROM invoices i
                    JOIN patients p ON p.id = i.patient_id
                    LEFT JOIN admissions a ON a.id = i.admission_id
                    LEFT JOIN departments d ON d.id = a.department_id
                    LEFT JOIN bal b ON b.invoice_id = i.id
                    WHERE i.organization_id = :orgId
                )
                """;
    }

    private static String where(Filter f, MapSqlParameterSource p) {
        List<String> c = new ArrayList<>();
        if (f.statuses() != null && !f.statuses().isEmpty()) {
            c.add("status IN (:statuses)");
            p.addValue("statuses", f.statuses().stream().map(Enum::name).toList());
        }
        if (f.paymentStatuses() != null && !f.paymentStatuses().isEmpty()) {
            c.add("payment_status IN (:paymentStatuses)");
            p.addValue("paymentStatuses", f.paymentStatuses().stream().map(Enum::name).toList());
        }
        if (f.from() != null) {
            c.add("created_at >= :from");
            p.addValue("from", Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            c.add("created_at < :to");
            p.addValue("to", Timestamp.from(f.to()));
        }
        if (f.departmentId() != null) {
            c.add("department_id = :departmentId");
            p.addValue("departmentId", f.departmentId());
        }
        if (f.patientId() != null) {
            c.add("patient_id = :patientId");
            p.addValue("patientId", f.patientId());
        }
        String like = Paging.likePattern(f.q());
        if (like != null) {
            c.add("(lower(invoice_number) LIKE :q OR lower(mrn) LIKE :q OR lower(patient_name) LIKE :q"
                    + " OR lower(COALESCE(admission_number, '')) LIKE :q)");
            p.addValue("q", like);
        }
        if (f.admissionStatuses() != null && !f.admissionStatuses().isEmpty()) {
            c.add("admission_status IN (:admissionStatuses)");
            p.addValue("admissionStatuses", f.admissionStatuses());
        }
        if (f.outstandingOnly()) {
            c.add("balance > 0 AND status <> 'CANCELLED'");
        }
        if (f.bucket() != null && !f.bucket().isBlank()) {
            c.add(switch (f.bucket()) {
                case "0-30" -> "age_days <= 30";
                case "31-60" -> "age_days BETWEEN 31 AND 60";
                case "61-90" -> "age_days BETWEEN 61 AND 90";
                case "90+" -> "age_days > 90";
                default -> throw ApiException.badRequest("INVALID_BUCKET", "Unknown aging bucket " + f.bucket());
            });
        }
        if (Boolean.TRUE.equals(f.overdueOnly())) {
            c.add("admission_status = 'DISCHARGED' AND age_days >= :overdueDays");
        }
        return c.isEmpty() ? "" : " WHERE " + String.join(" AND ", c);
    }

    /** Days after discharge from which an unpaid balance is overdue (hospital setting). */
    public int overdueDays(UUID orgId) {
        try {
            return Math.max(1, settings.getInt(orgId, SettingsService.BILLING_OVERDUE_DAYS));
        } catch (RuntimeException e) {
            return 30;
        }
    }

    private MapSqlParameterSource params(Filter f, ZoneId zone) {
        return new MapSqlParameterSource()
                .addValue("overdueDays", overdueDays(f.orgId()))
                .addValue("orgId", f.orgId())
                .addValue("tz", zone.getId())
                .addValue("today", LocalDate.now(zone));
    }

    public PageResponse<Row> page(Filter f, ZoneId zone, int page, int size, String sort, String defaultSort) {
        MapSqlParameterSource p = params(f, zone);
        String where = where(f, p);
        Long total = jdbc.queryForObject(baseSql() + "SELECT count(*) FROM v" + where, p, Long.class);
        String order = orderBy(sort, defaultSort);
        int safeSize = Math.min(Math.max(size, 1), Paging.MAX_SIZE);
        int safePage = Math.max(page, 0);
        p.addValue("limit", safeSize).addValue("offset", (long) safePage * safeSize);
        List<Row> rows = jdbc.query(baseSql() + "SELECT * FROM v" + where + order + " LIMIT :limit OFFSET :offset",
                p, ROW);
        long t = total == null ? 0 : total;
        return new PageResponse<>(rows, safePage, safeSize, t, (int) Math.ceil(t / (double) safeSize));
    }

    public List<Row> all(Filter f, ZoneId zone, String sort) {
        MapSqlParameterSource p = params(f, zone);
        String where = where(f, p);
        return jdbc.query(baseSql() + "SELECT * FROM v" + where + orderBy(sort, "createdAt,desc") + " LIMIT 5000", p, ROW);
    }

    /** Aging buckets over outstanding (balance > 0) invoices matching the filter. */
    public List<AgingBucket> aging(Filter f, ZoneId zone) {
        MapSqlParameterSource p = params(f, zone);
        Filter outstanding = new Filter(f.orgId(), f.statuses(), f.paymentStatuses(), f.from(), f.to(), f.departmentId(),
                f.q(), f.admissionStatuses(), true, f.patientId(), null, f.overdueOnly());
        String where = where(outstanding, p);
        String sql = baseSql() + """
                SELECT CASE WHEN age_days <= 30 THEN '0-30' WHEN age_days <= 60 THEN '31-60'
                            WHEN age_days <= 90 THEN '61-90' ELSE '90+' END AS bucket,
                       count(*) AS n, COALESCE(SUM(balance), 0) AS amount
                FROM v""" + where + " GROUP BY 1";
        Map<String, AgingBucket> found = new java.util.HashMap<>();
        jdbc.query(sql, p, rs -> {
            found.put(rs.getString("bucket"), new AgingBucket(rs.getString("bucket"), rs.getLong("n"),
                    Money.scale(rs.getBigDecimal("amount"))));
        });
        List<AgingBucket> result = new ArrayList<>();
        for (String b : List.of("0-30", "31-60", "61-90", "90+")) {
            result.add(found.getOrDefault(b, new AgingBucket(b, 0, Money.ZERO)));
        }
        return result;
    }

    /** Count and amount of outstanding invoices of discharged patients overdue (> 30 days). */
    public long[] overdueCount(Filter f, ZoneId zone, BigDecimal[] amountOut) {
        MapSqlParameterSource p = params(f, zone);
        Filter overdue = new Filter(f.orgId(), f.statuses(), f.paymentStatuses(), f.from(), f.to(), f.departmentId(),
                f.q(), f.admissionStatuses(), true, f.patientId(), null, true);
        String where = where(overdue, p);
        long[] n = new long[1];
        jdbc.query(baseSql() + "SELECT count(*) AS n, COALESCE(SUM(balance), 0) AS amount FROM v" + where, p, rs -> {
            n[0] = rs.getLong("n");
            amountOut[0] = Money.scale(rs.getBigDecimal("amount"));
        });
        return n;
    }

    private static String orderBy(String sort, String defaultSort) {
        String s = sort == null || sort.isBlank() ? defaultSort : sort;
        String[] parts = s.split(",");
        String col = SORTS.get(parts[0].trim());
        if (col == null) {
            throw ApiException.badRequest("INVALID_SORT", "Sorting by '" + parts[0].trim() + "' is not supported");
        }
        String dir = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc") ? "ASC" : "DESC";
        return " ORDER BY " + col + " " + dir + " NULLS LAST, id";
    }

    private static final RowMapper<Row> ROW = (rs, i) -> {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp finalized = rs.getTimestamp("finalized_at");
        Timestamp admitted = rs.getTimestamp("admitted_at");
        Timestamp discharged = rs.getTimestamp("discharged_at");
        Timestamp lastPay = rs.getTimestamp("last_payment_at");
        String admissionId = rs.getString("admission_id");
        InvoiceSummary s = new InvoiceSummary(
                UUID.fromString(rs.getString("id")), rs.getString("invoice_number"),
                Invoice.Status.valueOf(rs.getString("status")), Invoice.PayerType.valueOf(rs.getString("payer_type")),
                created == null ? null : created.toInstant(), finalized == null ? null : finalized.toInstant(),
                UUID.fromString(rs.getString("patient_id")), rs.getString("patient_name"), rs.getString("mrn"),
                admissionId == null ? null : UUID.fromString(admissionId), rs.getString("admission_number"),
                rs.getString("admission_status"), admitted == null ? null : admitted.toInstant(),
                discharged == null ? null : discharged.toInstant(), rs.getString("department_name"),
                Money.scale(rs.getBigDecimal("net_charges")), Money.scale(rs.getBigDecimal("credits")),
                Money.scale(rs.getBigDecimal("net_payments")), Money.scale(rs.getBigDecimal("balance")),
                LedgerService.PaymentStatus.valueOf(rs.getString("payment_status")),
                lastPay == null ? null : lastPay.toInstant());
        java.sql.Date ref = rs.getDate("ref_date");
        return new Row(s, ref == null ? null : ref.toLocalDate(), rs.getLong("age_days"));
    };
}
