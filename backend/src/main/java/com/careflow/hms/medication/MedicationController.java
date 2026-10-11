package com.careflow.hms.medication;

import com.careflow.hms.clinical.ClinicalDtos.ReasonRequest;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.medication.MedicationDtos.AdministerRequest;
import com.careflow.hms.medication.MedicationDtos.DoseView;
import com.careflow.hms.medication.MedicationDtos.ExceptionRequest;
import com.careflow.hms.medication.MedicationDtos.FollowUpRequest;
import com.careflow.hms.medication.MedicationDtos.MedicationOption;
import com.careflow.hms.medication.MedicationDtos.ModifyRequest;
import com.careflow.hms.medication.MedicationDtos.OrderRequest;
import com.careflow.hms.medication.MedicationDtos.OrderView;
import com.careflow.hms.medication.MedicationDtos.PrnDoseRequest;
import com.careflow.hms.medication.MedicationDtos.ReleaseOrderRequest;
import com.careflow.hms.medication.MedicationDtos.RoundsView;
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

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Tag(name = "Medication orders (prescribing) and medication administration (MAR)")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MedicationController {

    private final MedicationOrderService orders;
    private final MarService mar;

    // ------------------------------------------------------------------ prescribing

    @Operation(summary = "Search the active catalogue for prescribing; annotates allergy conflicts for an admission")
    @GetMapping("/prescribing/medications")
    @PreAuthorize("hasAuthority('PRESCRIBE')")
    public List<MedicationOption> search(@RequestParam(required = false) String q, @RequestParam(required = false) UUID admissionId) {
        return orders.search(q, admissionId);
    }

    @GetMapping("/admissions/{id}/medication-orders")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public List<OrderView> forAdmission(@PathVariable UUID id, @RequestParam(required = false) Set<MedicationOrder.Status> status) {
        return orders.forAdmission(id, status);
    }

    @Operation(summary = "Prescribe. Rejects with 422 ALLERGY_CONFLICT unless allergyOverrideReason is documented.")
    @PostMapping("/admissions/{id}/medication-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PRESCRIBE')")
    public OrderView prescribe(@PathVariable UUID id, @Valid @RequestBody OrderRequest body) {
        return orders.prescribe(id, body);
    }

    @Operation(summary = "Orders across the caller's patients (default ACTIVE + ON_HOLD)")
    @GetMapping("/medication-orders/my")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public PageResponse<OrderView> mine(@RequestParam(required = false) Set<MedicationOrder.Status> status,
                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return orders.mine(status, page, size);
    }

    @GetMapping("/medication-orders/{id}")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public OrderView get(@PathVariable UUID id) {
        return orders.get(id);
    }

    @Operation(summary = "Modify = discontinue this order and create a replacement (replacesOrderId)")
    @PostMapping("/medication-orders/{id}/modify")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PRESCRIBE')")
    public OrderView modify(@PathVariable UUID id, @Valid @RequestBody ModifyRequest body) {
        return orders.modify(id, body.order(), body.reason());
    }

    @PostMapping("/medication-orders/{id}/hold")
    @PreAuthorize("hasAuthority('PRESCRIBE')")
    public OrderView hold(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return orders.hold(id, body.reason());
    }

    @PostMapping("/medication-orders/{id}/resume")
    @PreAuthorize("hasAuthority('PRESCRIBE')")
    public OrderView resume(@PathVariable UUID id) {
        return orders.resume(id);
    }

    @PostMapping("/medication-orders/{id}/discontinue")
    @PreAuthorize("hasAuthority('PRESCRIBE')")
    public OrderView discontinue(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return orders.discontinue(id, body.reason());
    }

    @PostMapping("/medication-orders/{id}/release")
    @PreAuthorize("hasAuthority('RELEASE_TO_PATIENT')")
    public OrderView release(@PathVariable UUID id, @Valid @RequestBody ReleaseOrderRequest body) {
        return orders.release(id, body.patientInstructions());
    }

    // ------------------------------------------------------------------ MAR

    @Operation(summary = "Medication round for the caller's patients: overdue / due / upcoming doses grouped by hour, plus PRN orders")
    @GetMapping("/mar/rounds")
    @PreAuthorize("hasAuthority('MED_ADMINISTER')")
    public RoundsView rounds(@RequestParam(defaultValue = "8") int hours, @RequestParam(required = false) UUID wardId) {
        return mar.rounds(hours, wardId);
    }

    @GetMapping("/admissions/{id}/mar")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public List<DoseView> admissionMar(@PathVariable UUID id, @RequestParam(required = false) Instant from,
                                       @RequestParam(required = false) Instant to) {
        return mar.forAdmission(id, from, to);
    }

    @Operation(summary = "Administration history (recorded doses and exceptions), newest first")
    @GetMapping("/mar/history")
    @PreAuthorize("hasAuthority('CLINICAL_READ')")
    public PageResponse<DoseView> history(@RequestParam(required = false) UUID admissionId,
                                          @RequestParam(required = false) Set<MedicationAdministration.Status> status,
                                          @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return mar.history(admissionId, status, from, to, page, size);
    }

    @PostMapping("/mar/{id}/administer")
    @PreAuthorize("hasAuthority('MED_ADMINISTER')")
    public DoseView administer(@PathVariable UUID id, @Valid @RequestBody AdministerRequest body) {
        return mar.administer(id, body);
    }

    @Operation(summary = "Record DELAYED / REFUSED / MISSED / HELD with a mandatory reason (never changes the prescription)")
    @PostMapping("/mar/{id}/exception")
    @PreAuthorize("hasAuthority('MED_ADMINISTER')")
    public DoseView exception(@PathVariable UUID id, @Valid @RequestBody ExceptionRequest body) {
        return mar.recordException(id, body);
    }

    @Operation(summary = "Record an ad hoc dose against an active PRN order")
    @PostMapping("/medication-orders/{id}/prn-doses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MED_ADMINISTER')")
    public DoseView prnDose(@PathVariable UUID id, @Valid @RequestBody PrnDoseRequest body) {
        return mar.recordAdHoc(id, body);
    }

    @Operation(summary = "Medication exception queue (delayed / refused / missed / held / overdue) for the caller's patients")
    @GetMapping("/mar/exceptions")
    @PreAuthorize("hasAnyAuthority('MED_ADMINISTER','PRESCRIBE')")
    public PageResponse<DoseView> exceptions(@RequestParam(required = false) Set<MedicationAdministration.FollowUpStatus> followUp,
                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return mar.exceptionQueue(followUp, page, size);
    }

    @PostMapping("/mar/{id}/follow-up")
    @PreAuthorize("hasAnyAuthority('MED_ADMINISTER','PRESCRIBE')")
    public DoseView followUp(@PathVariable UUID id, @Valid @RequestBody FollowUpRequest body) {
        return mar.updateFollowUp(id, body);
    }
}
