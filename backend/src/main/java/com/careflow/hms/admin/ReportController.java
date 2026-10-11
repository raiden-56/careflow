package com.careflow.hms.admin;

import com.careflow.hms.admin.AdminDashboardService.AdminDashboard;
import com.careflow.hms.admin.ReportDtos.ClinicalQualityReport;
import com.careflow.hms.admin.ReportDtos.OperationalReport;
import com.careflow.hms.audit.AuditEvent;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.org.OrganizationRepository;
import com.careflow.hms.security.TenantContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Admin dashboard and operational reports")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReportController {

    private final AdminDashboardService dashboard;
    private final OperationalReportService reports;
    private final ReportExporter exporter;
    private final OrganizationRepository organizations;
    private final AuditService audit;

    @GetMapping("/dashboard/admin")
    @PreAuthorize("hasAuthority('REPORT_OPERATIONAL')")
    public AdminDashboard adminDashboard() {
        return dashboard.dashboard();
    }

    @GetMapping("/reports/operational")
    @PreAuthorize("hasAuthority('REPORT_OPERATIONAL')")
    public OperationalReport operational(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) UUID departmentId,
                                         @RequestParam(required = false) UUID wardId) {
        return reports.operational(from, to, departmentId, wardId);
    }

    @GetMapping("/reports/clinical-quality")
    @PreAuthorize("hasAuthority('REPORT_CLINICAL')")
    public ClinicalQualityReport clinicalQuality(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                 @RequestParam(required = false) UUID departmentId,
                                                 @RequestParam(required = false) UUID wardId) {
        return reports.clinicalQuality(from, to, departmentId, wardId);
    }

    @GetMapping("/reports/operational/export")
    @PreAuthorize("hasAuthority('REPORT_OPERATIONAL')")
    public ResponseEntity<byte[]> exportOperational(@RequestParam(defaultValue = "csv") String format,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                    @RequestParam(required = false) UUID departmentId,
                                                    @RequestParam(required = false) UUID wardId) {
        OperationalReport r = reports.operational(from, to, departmentId, wardId);
        return export("Operational report", "operational-report", format, r.filter(), exporter.sections(r));
    }

    @GetMapping("/reports/clinical-quality/export")
    @PreAuthorize("hasAuthority('REPORT_CLINICAL')")
    public ResponseEntity<byte[]> exportClinical(@RequestParam(defaultValue = "csv") String format,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                 @RequestParam(required = false) UUID departmentId,
                                                 @RequestParam(required = false) UUID wardId) {
        ClinicalQualityReport r = reports.clinicalQuality(from, to, departmentId, wardId);
        return export("Clinical quality summary", "clinical-quality-report", format, r.filter(), exporter.sections(r));
    }

    private ResponseEntity<byte[]> export(String title, String baseName, String format, ReportDtos.ReportFilter filter,
                                          List<ReportExporter.Section> sections) {
        String hospital = organizations.findById(TenantContext.requireOrganizationId()).map(o -> o.getName()).orElse("Hospital");
        String fileName = baseName + "-" + filter.from() + "-to-" + filter.to();
        byte[] body;
        MediaType type;
        switch (format.toLowerCase()) {
            case "csv" -> {
                body = exporter.csv(title, filter, hospital, sections);
                type = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);
                fileName += ".csv";
            }
            case "pdf" -> {
                body = exporter.pdf(title, filter, hospital, sections);
                type = MediaType.APPLICATION_PDF;
                fileName += ".pdf";
            }
            default -> throw ApiException.badRequest("INVALID_FORMAT", "Format must be csv or pdf.");
        }
        audit.record("REPORT_EXPORTED", "REPORT", null, null, AuditEvent.Outcome.SUCCESS,
                title + " exported as " + format.toUpperCase(),
                Map.of("from", String.valueOf(filter.from()), "to", String.valueOf(filter.to()), "format", format.toLowerCase()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(type)
                .body(body);
    }
}
