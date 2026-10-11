package com.careflow.hms.billing;

import com.careflow.hms.billing.BillingDtos.DashboardStats;
import com.careflow.hms.billing.BillingDtos.OutstandingReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Set;

@Tag(name = "Billing - dashboard, receivables and financial reports")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BillingReportController {

    private final BillingReportService reports;

    @GetMapping("/dashboard/billing")
    @PreAuthorize("hasAnyAuthority('BILLING_READ','REPORT_FINANCIAL')")
    public DashboardStats dashboard() {
        return reports.dashboard();
    }

    @Operation(summary = "Outstanding balances with aging buckets (0-30 / 31-60 / 61-90 / 90+ days)")
    @GetMapping("/billing/outstanding")
    @PreAuthorize("hasAuthority('BILLING_READ')")
    public OutstandingReport outstanding(@RequestParam(required = false) Set<String> admissionStatus,
                                         @RequestParam(required = false) String bucket,
                                         @RequestParam(required = false) Boolean overdue,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) String sort) {
        return reports.outstanding(admissionStatus, bucket, overdue, q, page, size, sort);
    }

    @GetMapping("/reports/billing/collections")
    @PreAuthorize("hasAuthority('REPORT_FINANCIAL')")
    public BillingReportService.CollectionsReport collections(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.collections(reports.range(from, to));
    }

    @GetMapping("/reports/billing/charges-vs-payments")
    @PreAuthorize("hasAuthority('REPORT_FINANCIAL')")
    public BillingReportService.ChargesVsPaymentsReport chargesVsPayments(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.chargesVsPayments(reports.range(from, to));
    }

    @GetMapping("/reports/billing/receivables")
    @PreAuthorize("hasAuthority('REPORT_FINANCIAL')")
    public BillingReportService.ReceivablesReport receivables(@RequestParam(required = false) Set<String> admissionStatus) {
        return reports.receivables(admissionStatus);
    }

    @GetMapping("/reports/billing/adjustments")
    @PreAuthorize("hasAuthority('REPORT_FINANCIAL')")
    public BillingReportService.AdjustmentsReport adjustments(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.adjustments(reports.range(from, to));
    }

    @GetMapping("/reports/billing/claims")
    @PreAuthorize("hasAuthority('REPORT_FINANCIAL')")
    public BillingReportService.ClaimsReport claims(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.claims(reports.range(from, to));
    }

    @GetMapping("/reports/billing/revenue")
    @PreAuthorize("hasAuthority('REPORT_FINANCIAL')")
    public BillingReportService.RevenueReport revenue(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.revenue(reports.range(from, to));
    }

    @Operation(summary = "CSV export of a financial report (collections, charges-vs-payments, receivables, adjustments, claims, revenue)")
    @GetMapping("/reports/billing/{report}/export")
    @PreAuthorize("hasAuthority('REPORT_FINANCIAL')")
    public ResponseEntity<byte[]> export(@PathVariable String report,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) Set<String> admissionStatus) {
        BillingReportService.Range range = reports.range(from, to);
        String csv = reports.csv(report, range, admissionStatus);
        String name = "careflow-" + report + "-" + range.from() + "-to-" + range.to() + ".csv";
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(out);
    }
}
