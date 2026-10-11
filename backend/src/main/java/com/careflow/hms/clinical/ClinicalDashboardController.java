package com.careflow.hms.clinical;

import com.careflow.hms.clinical.ClinicalDtos.AlertHistoryReport;
import com.careflow.hms.clinical.ClinicalDtos.DoctorDashboard;
import com.careflow.hms.clinical.ClinicalDtos.DocumentationReport;
import com.careflow.hms.clinical.ClinicalDtos.NurseDashboard;
import com.careflow.hms.clinical.ClinicalDtos.OutstandingReport;
import com.careflow.hms.clinical.ClinicalDtos.PatientRow;
import com.careflow.hms.clinical.ClinicalDtos.PortalRecords;
import com.careflow.hms.medication.MedicationDtos.OrderView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Clinical dashboards, reports and patient-portal clinical views")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ClinicalDashboardController {

    private final ClinicalOverviewService overview;

    @GetMapping("/dashboard/doctor")
    @PreAuthorize("hasRole('DOCTOR') and hasAuthority('CLINICAL_READ')")
    public DoctorDashboard doctor() {
        return overview.doctor();
    }

    @GetMapping("/dashboard/nurse")
    @PreAuthorize("hasRole('NURSE') and hasAuthority('CLINICAL_READ')")
    public NurseDashboard nurse() {
        return overview.nurse();
    }

    @Operation(summary = "The caller's patients with alert, medication, task and observation indicators")
    @GetMapping("/clinical/my-patients")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public List<PatientRow> myPatients() {
        return overview.myPatients();
    }

    // ------------------------------------------------------------------ clinical reports

    @GetMapping("/reports/clinical/documentation")
    @PreAuthorize("hasAuthority('REPORT_CLINICAL') and hasAuthority('CLINICAL_READ')")
    public DocumentationReport documentation() {
        return overview.documentation();
    }

    @GetMapping("/reports/clinical/outstanding-investigations")
    @PreAuthorize("hasAuthority('REPORT_CLINICAL') and hasAuthority('CLINICAL_READ')")
    public OutstandingReport outstanding() {
        return overview.outstanding();
    }

    @GetMapping("/reports/clinical/alerts")
    @PreAuthorize("hasAuthority('REPORT_CLINICAL') and hasAuthority('CLINICAL_READ')")
    public AlertHistoryReport alertHistory(@RequestParam(defaultValue = "7") int days) {
        return overview.alertHistory(days);
    }

    // ------------------------------------------------------------------ patient portal (released content only)

    @Operation(summary = "Portal: released notes (plain-language summaries), released results and released plan summaries")
    @GetMapping("/portal/patients/{patientId}/records")
    @PreAuthorize("hasAuthority('PORTAL_ACCESS')")
    public PortalRecords portalRecords(@PathVariable UUID patientId) {
        return overview.portalRecords(patientId);
    }

    @Operation(summary = "Portal: released prescriptions with patient instructions")
    @GetMapping("/portal/patients/{patientId}/medications")
    @PreAuthorize("hasAuthority('PORTAL_ACCESS')")
    public List<OrderView> portalMedications(@PathVariable UUID patientId) {
        return overview.portalMedications(patientId);
    }
}
