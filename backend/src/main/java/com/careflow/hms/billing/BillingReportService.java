package com.careflow.hms.billing;

import com.careflow.hms.billing.BillingDtos.AgingBucket;
import com.careflow.hms.billing.BillingDtos.DailyAmount;
import com.careflow.hms.billing.BillingDtos.DashboardStats;
import com.careflow.hms.billing.BillingDtos.OutstandingReport;
import com.careflow.hms.billing.BillingDtos.OutstandingRow;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.Money;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Financial reports. All figures are aggregated from the append-only ledger (or the records that
 * feed it) in the database. Labels keep three different things apart:
 * <ul>
 *   <li><b>collections</b> - money received (payments net of payment reversals);</li>
 *   <li><b>posted charges</b> - revenue billed (charges net of charge reversals);</li>
 *   <li><b>adjustments</b> - discounts, write-offs, credit/debit notes and refunds (approved only).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BillingReportService {

    private final NamedParameterJdbcTemplate jdbc;
    private final InvoiceQueries queries;
    private final BillingSupport support;
    private final PaymentRepository payments;
    private final BillingViews views;

    public record Range(LocalDate from, LocalDate to) {
    }

    // ------------------------------------------------------------------ report records

    public record CollectionRow(LocalDate date, Payment.Method method, long receipts, BigDecimal received,
                                BigDecimal reversed, BigDecimal net) {
    }

    public record MethodTotal(Payment.Method method, long receipts, BigDecimal received, BigDecimal reversed, BigDecimal net) {
    }

    public record CollectionsReport(LocalDate from, LocalDate to, List<CollectionRow> rows, List<MethodTotal> byMethod,
                                    List<DailyAmount> byDay, BigDecimal totalReceived, BigDecimal totalReversed,
                                    BigDecimal totalNet, String basis) {
    }

    public record ChargesVsPaymentsRow(LocalDate date, BigDecimal postedCharges, BigDecimal collections,
                                       BigDecimal adjustments, BigDecimal insuranceSettlements) {
    }

    public record ChargesVsPaymentsReport(LocalDate from, LocalDate to, List<ChargesVsPaymentsRow> rows,
                                          BigDecimal totalPostedCharges, BigDecimal totalCollections,
                                          BigDecimal totalAdjustments, BigDecimal totalInsurance, String basis) {
    }

    public record AdjustmentRow(UUID id, String adjustmentNumber, FinancialAdjustment.Type type,
                                FinancialAdjustment.Status status, BigDecimal amount, String invoiceNumber,
                                UUID invoiceId, String patientName, String mrn, String reason, String refundMethod,
                                String requestedBy, java.time.Instant requestedAt, String decidedBy,
                                java.time.Instant decidedAt) {
    }

    public record AdjustmentTypeTotal(FinancialAdjustment.Type type, long approvedCount, BigDecimal approvedAmount,
                                      long pendingCount, BigDecimal pendingAmount, long rejectedCount) {
    }

    public record AdjustmentsReport(LocalDate from, LocalDate to, List<AdjustmentTypeTotal> byType,
                                    List<AdjustmentRow> rows, BigDecimal approvedReductions,
                                    BigDecimal approvedRefunds, BigDecimal approvedDebitNotes, String basis) {
    }

    public record ClaimStatusRow(InsuranceClaim.Status status, long count, BigDecimal claimed, BigDecimal approved,
                                 BigDecimal settled, BigDecimal pendingSettlement) {
    }

    public record ClaimsReport(LocalDate from, LocalDate to, List<ClaimStatusRow> byStatus, long totalClaims,
                               BigDecimal totalClaimed, BigDecimal totalApproved, BigDecimal totalSettled,
                               BigDecimal totalPendingSettlement, String basis) {
    }

    public record RevenueRow(String key, String label, BigDecimal amount, long lines) {
    }

    public record RevenueReport(LocalDate from, LocalDate to, List<RevenueRow> byCategory, List<RevenueRow> byDepartment,
                                BigDecimal total, String basis) {
    }

    public record ReceivablesReport(LocalDate asOf, List<AgingBucket> buckets, BigDecimal total, long count,
                                    long overdueCount, BigDecimal overdueAmount, List<OutstandingRow> rows, String basis) {
    }

    // ------------------------------------------------------------------ helpers

    public Range range(LocalDate from, LocalDate to) {
        LocalDate today = support.today();
        LocalDate t = to == null ? today : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        if (f.isAfter(t)) {
            throw ApiException.badRequest("INVALID_RANGE", "The start date must be on or before the end date.");
        }
        if (ChronoUnit.DAYS.between(f, t) > 366) {
            throw ApiException.badRequest("RANGE_TOO_LONG", "Choose a range of at most one year.");
        }
        return new Range(f, t);
    }

    private MapSqlParameterSource params(Range r) {
        ZoneId zone = support.zone();
        return new MapSqlParameterSource()
                .addValue("orgId", TenantContext.requireOrganizationId())
                .addValue("tz", zone.getId())
                .addValue("from", Timestamp.from(r.from().atStartOfDay(zone).toInstant()))
                .addValue("to", Timestamp.from(r.to().plusDays(1).atStartOfDay(zone).toInstant()));
    }

    private static BigDecimal m(BigDecimal v) {
        return Money.scale(v == null ? BigDecimal.ZERO : v);
    }

    // ------------------------------------------------------------------ reports

    @Transactional(readOnly = true)
    public CollectionsReport collections(Range r) {
        List<CollectionRow> rows = jdbc.query("""
                SELECT (l.created_at AT TIME ZONE CAST(:tz AS text))::date AS day, p.method,
                       COUNT(*) FILTER (WHERE l.entry_type = 'PAYMENT') AS receipts,
                       COALESCE(SUM(CASE WHEN l.entry_type = 'PAYMENT' THEN -l.amount ELSE 0 END), 0) AS received,
                       COALESCE(SUM(CASE WHEN l.entry_type = 'PAYMENT_REVERSAL' THEN l.amount ELSE 0 END), 0) AS reversed
                FROM ledger_entries l JOIN payments p ON p.id = l.reference_id
                WHERE l.organization_id = :orgId AND l.entry_type IN ('PAYMENT','PAYMENT_REVERSAL')
                  AND l.created_at >= :from AND l.created_at < :to
                GROUP BY 1, 2 ORDER BY 1, 2
                """, params(r), (rs, i) -> {
            BigDecimal received = m(rs.getBigDecimal("received"));
            BigDecimal reversed = m(rs.getBigDecimal("reversed"));
            return new CollectionRow(rs.getDate("day").toLocalDate(), Payment.Method.valueOf(rs.getString("method")),
                    rs.getLong("receipts"), received, reversed, m(received.subtract(reversed)));
        });
        Map<Payment.Method, BigDecimal[]> byMethod = new LinkedHashMap<>();
        Map<Payment.Method, Long> receipts = new LinkedHashMap<>();
        Map<LocalDate, BigDecimal> byDay = new TreeMap<>();
        for (LocalDate d = r.from(); !d.isAfter(r.to()); d = d.plusDays(1)) {
            byDay.put(d, Money.ZERO);
        }
        BigDecimal received = Money.ZERO;
        BigDecimal reversed = Money.ZERO;
        for (CollectionRow row : rows) {
            BigDecimal[] t = byMethod.computeIfAbsent(row.method(), k -> new BigDecimal[]{Money.ZERO, Money.ZERO});
            t[0] = t[0].add(row.received());
            t[1] = t[1].add(row.reversed());
            receipts.merge(row.method(), row.receipts(), Long::sum);
            byDay.merge(row.date(), row.net(), BigDecimal::add);
            received = received.add(row.received());
            reversed = reversed.add(row.reversed());
        }
        List<MethodTotal> methods = byMethod.entrySet().stream()
                .map(e -> new MethodTotal(e.getKey(), receipts.getOrDefault(e.getKey(), 0L), m(e.getValue()[0]),
                        m(e.getValue()[1]), m(e.getValue()[0].subtract(e.getValue()[1]))))
                .sorted((a, b) -> b.net().compareTo(a.net())).toList();
        return new CollectionsReport(r.from(), r.to(), rows, methods,
                byDay.entrySet().stream().map(e -> new DailyAmount(e.getKey(), m(e.getValue()))).toList(),
                m(received), m(reversed), m(received.subtract(reversed)),
                "Collections = payments received less payment reversals (e.g. bounced cheques), by the date they were recorded. Refunds are reported under adjustments.");
    }

    @Transactional(readOnly = true)
    public ChargesVsPaymentsReport chargesVsPayments(Range r) {
        Map<LocalDate, ChargesVsPaymentsRow> rows = new TreeMap<>();
        for (LocalDate d = r.from(); !d.isAfter(r.to()); d = d.plusDays(1)) {
            rows.put(d, new ChargesVsPaymentsRow(d, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO));
        }
        jdbc.query("""
                SELECT (created_at AT TIME ZONE CAST(:tz AS text))::date AS day,
                       COALESCE(SUM(CASE WHEN entry_type IN ('CHARGE','CHARGE_REVERSAL') THEN amount ELSE 0 END), 0) AS charges,
                       COALESCE(-SUM(CASE WHEN entry_type IN ('PAYMENT','PAYMENT_REVERSAL') THEN amount ELSE 0 END), 0) AS collections,
                       COALESCE(-SUM(CASE WHEN entry_type IN ('DISCOUNT','WRITE_OFF','CREDIT_NOTE','DEBIT_NOTE','REFUND') THEN amount ELSE 0 END), 0) AS adjustments,
                       COALESCE(-SUM(CASE WHEN entry_type = 'INSURANCE_SETTLEMENT' THEN amount ELSE 0 END), 0) AS insurance
                FROM ledger_entries
                WHERE organization_id = :orgId AND created_at >= :from AND created_at < :to
                GROUP BY 1
                """, params(r), rs -> {
            LocalDate d = rs.getDate("day").toLocalDate();
            rows.put(d, new ChargesVsPaymentsRow(d, m(rs.getBigDecimal("charges")), m(rs.getBigDecimal("collections")),
                    m(rs.getBigDecimal("adjustments")), m(rs.getBigDecimal("insurance"))));
        });
        List<ChargesVsPaymentsRow> list = new ArrayList<>(rows.values());
        return new ChargesVsPaymentsReport(r.from(), r.to(), list,
                m(list.stream().map(ChargesVsPaymentsRow::postedCharges).reduce(BigDecimal.ZERO, BigDecimal::add)),
                m(list.stream().map(ChargesVsPaymentsRow::collections).reduce(BigDecimal.ZERO, BigDecimal::add)),
                m(list.stream().map(ChargesVsPaymentsRow::adjustments).reduce(BigDecimal.ZERO, BigDecimal::add)),
                m(list.stream().map(ChargesVsPaymentsRow::insuranceSettlements).reduce(BigDecimal.ZERO, BigDecimal::add)),
                "Posted charges are net of reversals; collections are payments net of reversals; adjustments are the net reduction "
                        + "from approved discounts, write-offs and credit notes less refunds and debit notes; insurance is settlements received. "
                        + "All by ledger posting date.");
    }

    @Transactional(readOnly = true)
    public AdjustmentsReport adjustments(Range r) {
        List<AdjustmentRow> rows = jdbc.query("""
                SELECT a.id, a.adjustment_number, a.adjustment_type, a.status, a.amount, i.invoice_number, i.id AS invoice_id,
                       p.first_name || ' ' || p.last_name AS patient_name, p.mrn, a.reason, a.refund_method,
                       ru.full_name AS requested_by, a.requested_at, du.full_name AS decided_by, a.decided_at
                FROM financial_adjustments a
                JOIN invoices i ON i.id = a.invoice_id
                JOIN patients p ON p.id = i.patient_id
                JOIN users ru ON ru.id = a.requested_by
                LEFT JOIN users du ON du.id = a.decided_by
                WHERE a.organization_id = :orgId AND a.requested_at >= :from AND a.requested_at < :to
                ORDER BY a.requested_at DESC
                """, params(r), (rs, i) -> new AdjustmentRow(UUID.fromString(rs.getString("id")), rs.getString("adjustment_number"),
                FinancialAdjustment.Type.valueOf(rs.getString("adjustment_type")),
                FinancialAdjustment.Status.valueOf(rs.getString("status")), m(rs.getBigDecimal("amount")),
                rs.getString("invoice_number"), UUID.fromString(rs.getString("invoice_id")), rs.getString("patient_name"),
                rs.getString("mrn"), rs.getString("reason"), rs.getString("refund_method"), rs.getString("requested_by"),
                rs.getTimestamp("requested_at").toInstant(), rs.getString("decided_by"),
                rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant()));
        List<AdjustmentTypeTotal> byType = new ArrayList<>();
        BigDecimal reductions = Money.ZERO;
        BigDecimal refunds = Money.ZERO;
        BigDecimal debits = Money.ZERO;
        for (FinancialAdjustment.Type t : FinancialAdjustment.Type.values()) {
            List<AdjustmentRow> ofType = rows.stream().filter(x -> x.type() == t).toList();
            BigDecimal approved = ofType.stream().filter(x -> x.status() == FinancialAdjustment.Status.APPROVED)
                    .map(AdjustmentRow::amount).reduce(Money.ZERO, BigDecimal::add);
            BigDecimal pending = ofType.stream().filter(x -> x.status() == FinancialAdjustment.Status.PENDING)
                    .map(AdjustmentRow::amount).reduce(Money.ZERO, BigDecimal::add);
            byType.add(new AdjustmentTypeTotal(t,
                    ofType.stream().filter(x -> x.status() == FinancialAdjustment.Status.APPROVED).count(), m(approved),
                    ofType.stream().filter(x -> x.status() == FinancialAdjustment.Status.PENDING).count(), m(pending),
                    ofType.stream().filter(x -> x.status() == FinancialAdjustment.Status.REJECTED).count()));
            switch (t) {
                case REFUND -> refunds = refunds.add(approved);
                case DEBIT_NOTE -> debits = debits.add(approved);
                default -> reductions = reductions.add(approved);
            }
        }
        return new AdjustmentsReport(r.from(), r.to(), byType, rows, m(reductions), m(refunds), m(debits),
                "Adjustments requested in the period. Only approved adjustments affect balances; pending and rejected ones are listed for oversight.");
    }

    @Transactional(readOnly = true)
    public ClaimsReport claims(Range r) {
        Map<InsuranceClaim.Status, ClaimStatusRow> map = new LinkedHashMap<>();
        for (InsuranceClaim.Status s : InsuranceClaim.Status.values()) {
            map.put(s, new ClaimStatusRow(s, 0, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO));
        }
        jdbc.query("""
                SELECT status, count(*) AS n, COALESCE(SUM(claimed_amount), 0) AS claimed,
                       COALESCE(SUM(approved_amount), 0) AS approved, COALESCE(SUM(settled_amount), 0) AS settled,
                       COALESCE(SUM(CASE WHEN status IN ('APPROVED','PARTIALLY_APPROVED')
                                         THEN GREATEST(COALESCE(approved_amount, 0) - settled_amount, 0) ELSE 0 END), 0) AS pending
                FROM insurance_claims
                WHERE organization_id = :orgId AND created_at >= :from AND created_at < :to
                GROUP BY status
                """, params(r), rs -> {
            InsuranceClaim.Status s = InsuranceClaim.Status.valueOf(rs.getString("status"));
            map.put(s, new ClaimStatusRow(s, rs.getLong("n"), m(rs.getBigDecimal("claimed")), m(rs.getBigDecimal("approved")),
                    m(rs.getBigDecimal("settled")), m(rs.getBigDecimal("pending"))));
        });
        List<ClaimStatusRow> rows = new ArrayList<>(map.values());
        return new ClaimsReport(r.from(), r.to(), rows, rows.stream().mapToLong(ClaimStatusRow::count).sum(),
                m(rows.stream().map(ClaimStatusRow::claimed).reduce(BigDecimal.ZERO, BigDecimal::add)),
                m(rows.stream().map(ClaimStatusRow::approved).reduce(BigDecimal.ZERO, BigDecimal::add)),
                m(rows.stream().map(ClaimStatusRow::settled).reduce(BigDecimal.ZERO, BigDecimal::add)),
                m(rows.stream().map(ClaimStatusRow::pendingSettlement).reduce(BigDecimal.ZERO, BigDecimal::add)),
                "Claims created in the period, by current status. Approved amounts reduce the patient's balance only when settled.");
    }

    @Transactional(readOnly = true)
    public RevenueReport revenue(Range r) {
        List<RevenueRow> byCategory = jdbc.query("""
                SELECT li.category AS k, COALESCE(SUM(l.amount), 0) AS amount,
                       COUNT(*) FILTER (WHERE l.entry_type = 'CHARGE') - COUNT(*) FILTER (WHERE l.entry_type = 'CHARGE_REVERSAL') AS n
                FROM ledger_entries l JOIN invoice_line_items li ON li.id = l.reference_id
                WHERE l.organization_id = :orgId AND l.entry_type IN ('CHARGE','CHARGE_REVERSAL')
                  AND l.created_at >= :from AND l.created_at < :to
                GROUP BY li.category ORDER BY 2 DESC
                """, params(r), (rs, i) -> new RevenueRow(rs.getString("k"), categoryLabel(rs.getString("k")),
                m(rs.getBigDecimal("amount")), rs.getLong("n")));
        List<RevenueRow> byDepartment = jdbc.query("""
                SELECT COALESCE(CAST(d.id AS text), 'NONE') AS k, COALESCE(d.name, 'No department') AS label,
                       COALESCE(SUM(l.amount), 0) AS amount,
                       COUNT(*) FILTER (WHERE l.entry_type = 'CHARGE') - COUNT(*) FILTER (WHERE l.entry_type = 'CHARGE_REVERSAL') AS n
                FROM ledger_entries l
                JOIN invoices i ON i.id = l.invoice_id
                LEFT JOIN admissions a ON a.id = i.admission_id
                LEFT JOIN departments d ON d.id = a.department_id
                WHERE l.organization_id = :orgId AND l.entry_type IN ('CHARGE','CHARGE_REVERSAL')
                  AND l.created_at >= :from AND l.created_at < :to
                GROUP BY 1, 2 ORDER BY 3 DESC
                """, params(r), (rs, i) -> new RevenueRow(rs.getString("k"), rs.getString("label"),
                m(rs.getBigDecimal("amount")), rs.getLong("n")));
        return new RevenueReport(r.from(), r.to(), byCategory, byDepartment,
                m(byCategory.stream().map(RevenueRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add)),
                "Revenue = posted charges net of charge reversals, by ledger posting date (not cash collected).");
    }

    @Transactional(readOnly = true)
    public ReceivablesReport receivables(Collection<String> admissionStatuses) {
        ZoneId zone = support.zone();
        InvoiceQueries.Filter f = InvoiceQueries.Filter.builder().orgId(TenantContext.requireOrganizationId())
                .outstandingOnly(true).admissionStatuses(admissionStatuses).build();
        List<OutstandingRow> rows = queries.all(f, zone, "ageDays,desc").stream().map(this::outstandingRow).toList();
        BigDecimal[] overdueAmount = {Money.ZERO};
        long overdue = queries.overdueCount(f, zone, overdueAmount)[0];
        return new ReceivablesReport(support.today(), queries.aging(f, zone),
                m(rows.stream().map(x -> x.invoice().balance()).reduce(BigDecimal.ZERO, BigDecimal::add)), rows.size(),
                overdue, overdueAmount[0], rows,
                "Invoices with a positive ledger balance. Age is counted from the discharge date (or the invoice date while admitted). "
                        + "Overdue = discharged " + queries.overdueDays(TenantContext.requireOrganizationId()) + " or more days ago.");
    }

    @Transactional(readOnly = true)
    public OutstandingReport outstanding(Collection<String> admissionStatuses, String bucket, Boolean overdueOnly, String q,
                                         int page, int size, String sort) {
        ZoneId zone = support.zone();
        InvoiceQueries.Filter f = InvoiceQueries.Filter.builder().orgId(TenantContext.requireOrganizationId())
                .outstandingOnly(true).admissionStatuses(admissionStatuses).q(q).build();
        InvoiceQueries.Filter paged = InvoiceQueries.Filter.builder().orgId(f.orgId()).outstandingOnly(true)
                .admissionStatuses(admissionStatuses).q(q).bucket(bucket).overdueOnly(overdueOnly).build();
        PageResponse<InvoiceQueries.Row> rows = queries.page(paged, zone, page, size, sort, "ageDays,desc");
        List<AgingBucket> buckets = queries.aging(f, zone);
        BigDecimal[] overdueAmount = {Money.ZERO};
        long overdue = queries.overdueCount(f, zone, overdueAmount)[0];
        BigDecimal total = m(buckets.stream().map(AgingBucket::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        long count = buckets.stream().mapToLong(AgingBucket::count).sum();
        return new OutstandingReport(buckets, total, count, overdue, overdueAmount[0],
                new PageResponse<>(rows.content().stream().map(this::outstandingRow).toList(), rows.page(), rows.size(),
                        rows.totalElements(), rows.totalPages()));
    }

    private OutstandingRow outstandingRow(InvoiceQueries.Row r) {
        long age = r.ageDays();
        String bucket = age <= 30 ? "0-30" : age <= 60 ? "31-60" : age <= 90 ? "61-90" : "90+";
        boolean overdue = "DISCHARGED".equals(r.summary().admissionStatus())
                && age >= queries.overdueDays(TenantContext.requireOrganizationId());
        return new OutstandingRow(r.summary(), r.referenceDate(), age, bucket, overdue);
    }

    @Transactional(readOnly = true)
    public DashboardStats dashboard() {
        ZoneId zone = support.zone();
        UUID orgId = TenantContext.requireOrganizationId();
        LocalDate today = support.today();
        CollectionsReport trend = collections(new Range(today.minusDays(13), today));
        DailyAmount todayAmount = trend.byDay().get(trend.byDay().size() - 1);
        long todayCount = trend.rows().stream().filter(x -> x.date().equals(today)).mapToLong(CollectionRow::receipts).sum();

        InvoiceQueries.Filter outstandingF = InvoiceQueries.Filter.builder().orgId(orgId).outstandingOnly(true).build();
        List<AgingBucket> aging = queries.aging(outstandingF, zone);
        BigDecimal outstanding = m(aging.stream().map(AgingBucket::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        long outstandingCount = aging.stream().mapToLong(AgingBucket::count).sum();

        MapSqlParameterSource p = new MapSqlParameterSource("orgId", orgId);
        Map<String, Object> pending = jdbc.queryForMap("""
                SELECT count(*) AS n, COALESCE(SUM(amount), 0) AS amount FROM financial_adjustments
                WHERE organization_id = :orgId AND status = 'PENDING'
                """, p);
        Map<String, Object> claims = jdbc.queryForMap("""
                SELECT count(*) AS n,
                       COALESCE(SUM(CASE WHEN status IN ('APPROVED','PARTIALLY_APPROVED')
                                         THEN GREATEST(COALESCE(approved_amount, 0) - settled_amount, 0) ELSE 0 END), 0) AS pending
                FROM insurance_claims WHERE organization_id = :orgId AND status NOT IN ('SETTLED','REJECTED')
                """, p);
        Long openRequests = jdbc.queryForObject("""
                SELECT count(*) FROM billing_requests WHERE organization_id = :orgId AND status IN ('OPEN','IN_REVIEW')
                """, p, Long.class);
        Long openInvoices = jdbc.queryForObject("SELECT count(*) FROM invoices WHERE organization_id = :orgId AND status = 'OPEN'", p, Long.class);
        PageResponse<InvoiceQueries.Row> discharged = queries.page(InvoiceQueries.Filter.builder().orgId(orgId)
                .outstandingOnly(true).admissionStatuses(List.of("DISCHARGED")).build(), zone, 0, 1, null, "ageDays,desc");
        List<AgingBucket> dischargedAging = queries.aging(InvoiceQueries.Filter.builder().orgId(orgId)
                .outstandingOnly(true).admissionStatuses(List.of("DISCHARGED")).build(), zone);
        BigDecimal dischargedAmount = m(dischargedAging.stream().map(AgingBucket::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        var recent = payments.search(orgId, java.time.Instant.parse("2000-01-01T00:00:00Z"), java.time.Instant.now().plusSeconds(86_400),
                EnumSet.allOf(Payment.Method.class), EnumSet.allOf(Payment.Status.class), null,
                PageRequest.of(0, 6, Sort.by(Sort.Direction.DESC, "receivedAt")));
        return new DashboardStats(todayAmount.amount(), todayCount, outstanding, outstandingCount,
                ((Number) pending.get("n")).longValue(), m((BigDecimal) pending.get("amount")),
                ((Number) claims.get("n")).longValue(), m((BigDecimal) claims.get("pending")),
                discharged.totalElements(), dischargedAmount, openRequests == null ? 0 : openRequests,
                openInvoices == null ? 0 : openInvoices, trend.byDay(), views.paymentViews(recent.getContent()), aging);
    }

    // ------------------------------------------------------------------ CSV

    public String csv(String report, Range r, Collection<String> admissionStatuses) {
        StringBuilder sb = new StringBuilder();
        switch (report) {
            case "collections" -> {
                CollectionsReport c = collections(r);
                line(sb, "Date", "Payment method", "Receipts", "Received (INR)", "Reversed (INR)", "Net collections (INR)");
                c.rows().forEach(x -> line(sb, x.date(), x.method(), x.receipts(), x.received(), x.reversed(), x.net()));
                line(sb, "TOTAL", "", "", c.totalReceived(), c.totalReversed(), c.totalNet());
            }
            case "charges-vs-payments" -> {
                ChargesVsPaymentsReport c = chargesVsPayments(r);
                line(sb, "Date", "Posted charges net of reversals (INR)", "Collections net of reversals (INR)",
                        "Adjustments net reduction (INR)", "Insurance settlements (INR)");
                c.rows().forEach(x -> line(sb, x.date(), x.postedCharges(), x.collections(), x.adjustments(), x.insuranceSettlements()));
                line(sb, "TOTAL", c.totalPostedCharges(), c.totalCollections(), c.totalAdjustments(), c.totalInsurance());
            }
            case "receivables" -> {
                ReceivablesReport c = receivables(admissionStatuses);
                line(sb, "Invoice", "Patient", "MRN", "Admission", "Admission status", "Reference date", "Age (days)",
                        "Aging bucket", "Overdue", "Posted charges (INR)", "Paid and credited (INR)", "Outstanding balance (INR)");
                c.rows().forEach(x -> line(sb, x.invoice().invoiceNumber(), x.invoice().patientName(), x.invoice().mrn(),
                        x.invoice().admissionNumber(), x.invoice().admissionStatus(), x.referenceDate(), x.ageDays(), x.bucket(),
                        x.overdue() ? "Yes" : "No", x.invoice().netCharges(), x.invoice().credits(), x.invoice().balance()));
                line(sb, "TOTAL", "", "", "", "", "", "", "", "", "", "", c.total());
            }
            case "adjustments" -> {
                AdjustmentsReport c = adjustments(r);
                line(sb, "Number", "Type", "Status", "Amount (INR)", "Invoice", "Patient", "MRN", "Reason", "Refund method",
                        "Requested by", "Requested at", "Decided by", "Decided at");
                c.rows().forEach(x -> line(sb, x.adjustmentNumber(), x.type(), x.status(), x.amount(), x.invoiceNumber(),
                        x.patientName(), x.mrn(), x.reason(), x.refundMethod(), x.requestedBy(), x.requestedAt(), x.decidedBy(), x.decidedAt()));
            }
            case "claims" -> {
                ClaimsReport c = claims(r);
                line(sb, "Status", "Claims", "Claimed (INR)", "Approved (INR)", "Settled (INR)", "Approved, pending settlement (INR)");
                c.byStatus().forEach(x -> line(sb, x.status(), x.count(), x.claimed(), x.approved(), x.settled(), x.pendingSettlement()));
                line(sb, "TOTAL", c.totalClaims(), c.totalClaimed(), c.totalApproved(), c.totalSettled(), c.totalPendingSettlement());
            }
            case "revenue" -> {
                RevenueReport c = revenue(r);
                line(sb, "Group", "Name", "Posted charges net of reversals (INR)", "Charge lines");
                c.byCategory().forEach(x -> line(sb, "Category", x.label(), x.amount(), x.lines()));
                c.byDepartment().forEach(x -> line(sb, "Department", x.label(), x.amount(), x.lines()));
                line(sb, "TOTAL", "", c.total(), "");
            }
            default -> throw ApiException.notFound("Report");
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String s = cells[i] == null ? "" : String.valueOf(cells[i]);
            // Neutralise spreadsheet formula injection and quote when needed.
            if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) {
                s = "'" + s;
            }
            if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
                s = "\"" + s.replace("\"", "\"\"") + "\"";
            }
            sb.append(s);
        }
        sb.append("\r\n");
    }

    public static String categoryLabel(String category) {
        if (category == null) {
            return "Other";
        }
        return switch (category) {
            case "ROOM" -> "Room & bed";
            case "PROCEDURE" -> "Procedures";
            case "LAB" -> "Laboratory";
            case "PHARMACY" -> "Pharmacy";
            case "CONSULTATION" -> "Consultations";
            case "NURSING" -> "Nursing care";
            case "IMAGING" -> "Imaging";
            case "CONSUMABLE" -> "Consumables";
            default -> "Other services";
        };
    }
}
