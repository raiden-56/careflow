package com.careflow.hms.clinical;

import com.careflow.hms.clinical.ClinicalDtos.CatalogueItem;
import com.careflow.hms.clinical.ClinicalDtos.CollectRequest;
import com.careflow.hms.clinical.ClinicalDtos.LabOrderRequest;
import com.careflow.hms.clinical.ClinicalDtos.LabOrderView;
import com.careflow.hms.clinical.ClinicalDtos.LisResultPayload;
import com.careflow.hms.clinical.ClinicalDtos.ReasonRequest;
import com.careflow.hms.clinical.ClinicalDtos.ResultEntryRequest;
import com.careflow.hms.clinical.ClinicalDtos.ReviewRequest;
import com.careflow.hms.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Investigations (lab & imaging)")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InvestigationController {

    private final InvestigationService investigations;

    @Operation(summary = "Orderable catalogue from service tariffs (LAB / IMAGING by default; PROCEDURE on request)")
    @GetMapping("/investigations/catalogue")
    @PreAuthorize("hasAnyAuthority('LAB_ORDER','PROCEDURE_WRITE','CLINICAL_READ')")
    public List<CatalogueItem> catalogue(@RequestParam(required = false) String category, @RequestParam(required = false) String q) {
        return investigations.catalogue(category, q);
    }

    @GetMapping("/admissions/{id}/investigations")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public List<LabOrderView> forAdmission(@PathVariable UUID id) {
        return investigations.forAdmission(id);
    }

    @Operation(summary = "Order an investigation; posts the LAB_ORDER charge and creates a ward collection task")
    @PostMapping("/admissions/{id}/investigations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LAB_ORDER')")
    public LabOrderView order(@PathVariable UUID id, @Valid @RequestBody LabOrderRequest body) {
        return investigations.order(id, body);
    }

    @Operation(summary = "Worklists across the caller's patients: PENDING_COLLECTION, AWAITING_RESULT, AWAITING_REVIEW, OUTSTANDING, ALL")
    @GetMapping("/investigations/worklist")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public PageResponse<LabOrderView> worklist(@RequestParam(defaultValue = "OUTSTANDING") InvestigationService.Worklist view,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return investigations.worklist(view, page, size);
    }

    @GetMapping("/investigations/{id}")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public LabOrderView get(@PathVariable UUID id) {
        return investigations.get(id);
    }

    @PostMapping("/investigations/{id}/collect")
    @PreAuthorize("hasAuthority('LAB_COLLECT')")
    public LabOrderView collect(@PathVariable UUID id, @Valid @RequestBody(required = false) CollectRequest body) {
        return investigations.collect(id, body);
    }

    @Operation(summary = "Cancel; the charge is reversed if no specimen was collected yet")
    @PostMapping("/investigations/{id}/cancel")
    @PreAuthorize("hasAuthority('LAB_ORDER')")
    public LabOrderView cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return investigations.cancel(id, body.reason());
    }

    @PostMapping("/investigations/{id}/results")
    @PreAuthorize("hasAuthority('LAB_RESULT_ENTER')")
    public LabOrderView enterResults(@PathVariable UUID id, @Valid @RequestBody ResultEntryRequest body) {
        return investigations.enterResults(id, body);
    }

    @PostMapping("/investigations/{id}/review")
    @PreAuthorize("hasAuthority('LAB_RESULT_REVIEW')")
    public LabOrderView review(@PathVariable UUID id, @Valid @RequestBody(required = false) ReviewRequest body) {
        return investigations.review(id, body == null ? null : body.comment());
    }

    @PostMapping("/investigations/{id}/release")
    @PreAuthorize("hasAuthority('RELEASE_TO_PATIENT')")
    public LabOrderView release(@PathVariable UUID id) {
        return investigations.release(id);
    }

    /**
     * DEVELOPMENT LIS ADAPTER - a mock integration boundary, NOT a live laboratory interface. It lets a
     * developer or test harness post a result payload as if it came from a laboratory information system;
     * results are stored with source = LIS_ADAPTER and follow the same alerting/review workflow.
     */
    @Operation(summary = "[DEVELOPMENT MOCK] Ingest a result payload as if from a laboratory information system (source LIS_ADAPTER)")
    @PostMapping("/integrations/lis/dev-results")
    @PreAuthorize("hasAuthority('LAB_RESULT_ENTER')")
    public LabOrderView lisDevResult(@Valid @RequestBody LisResultPayload body) {
        return investigations.ingestLisResult(body);
    }
}
