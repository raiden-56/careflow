package com.careflow.hms.admin;

import com.careflow.hms.admin.AuditLogService.AuditDetail;
import com.careflow.hms.admin.AuditLogService.AuditFilter;
import com.careflow.hms.admin.AuditLogService.AuditRow;
import com.careflow.hms.audit.AuditEvent;
import com.careflow.hms.common.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.util.List;
import java.util.UUID;

@Tag(name = "Hospital audit log")
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('AUDIT_READ') and !hasRole('SUPER_ADMIN')")
public class AuditLogController {

    private final AuditLogService service;

    @GetMapping
    public PageResponse<AuditRow> search(@RequestParam(required = false) String actor,
                                         @RequestParam(required = false) String patientMrn,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) String entityType,
                                         @RequestParam(required = false) AuditEvent.Outcome outcome,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "25") int size,
                                         @RequestParam(required = false) String sort) {
        return service.search(new AuditFilter(actor, patientMrn, action, entityType, outcome, from, to), page, size, sort);
    }

    @GetMapping("/actions")
    public List<String> actions() {
        return service.actions();
    }

    @GetMapping("/{id}")
    public AuditDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String actor,
                                         @RequestParam(required = false) String patientMrn,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) String entityType,
                                         @RequestParam(required = false) AuditEvent.Outcome outcome,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        byte[] body = service.exportCsv(new AuditFilter(actor, patientMrn, action, entityType, outcome, from, to));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-log-" + LocalDate.now() + ".csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
