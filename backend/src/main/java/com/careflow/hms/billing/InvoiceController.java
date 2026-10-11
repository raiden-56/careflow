package com.careflow.hms.billing;

import com.careflow.hms.billing.BillingDtos.AddChargeRequest;
import com.careflow.hms.billing.BillingDtos.CreateInvoiceRequest;
import com.careflow.hms.billing.BillingDtos.EstimateRequest;
import com.careflow.hms.billing.BillingDtos.FinalizeRequest;
import com.careflow.hms.billing.BillingDtos.InvoiceDetail;
import com.careflow.hms.billing.BillingDtos.InvoiceSummary;
import com.careflow.hms.billing.BillingDtos.ReasonRequest;
import com.careflow.hms.billing.BillingDtos.RoomChargeResult;
import com.careflow.hms.billing.BillingDtos.TariffOption;
import com.careflow.hms.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Tag(name = "Billing - invoices, charges, estimates")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoices;
    private final BillingPdfService pdf;

    @Operation(summary = "Search invoices (balance and payment status are derived from the ledger)")
    @GetMapping("/invoices")
    @PreAuthorize("hasAuthority('BILLING_READ')")
    public PageResponse<InvoiceSummary> search(@RequestParam(required = false) Set<Invoice.Status> status,
                                               @RequestParam(required = false) Set<LedgerService.PaymentStatus> paymentStatus,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                               @RequestParam(required = false) UUID departmentId,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(required = false) String sort) {
        return invoices.search(status, paymentStatus, from, to, departmentId, q, page, size, sort);
    }

    @Operation(summary = "Create the invoice for an admission (when none is active)")
    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('BILLING_WRITE')")
    public InvoiceDetail create(@Valid @RequestBody CreateInvoiceRequest body) {
        return invoices.createForAdmission(body.admissionId());
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasAuthority('BILLING_READ')")
    public InvoiceDetail get(@PathVariable UUID id) {
        return invoices.detail(id);
    }

    @Operation(summary = "The active invoice of an admission")
    @GetMapping("/invoices/by-admission/{admissionId}")
    @PreAuthorize("hasAuthority('BILLING_READ')")
    public InvoiceDetail byAdmission(@PathVariable UUID admissionId) {
        return invoices.byAdmission(admissionId);
    }

    @Operation(summary = "Post a charge from an active tariff (price override requires a reason)")
    @PostMapping("/invoices/{id}/charges")
    @PreAuthorize("hasAuthority('BILLING_WRITE')")
    public InvoiceDetail addCharge(@PathVariable UUID id, @Valid @RequestBody AddChargeRequest body) {
        return invoices.addCharge(id, body);
    }

    @Operation(summary = "Reverse a posted charge (creates a negative reversal line; nothing is deleted)")
    @PostMapping("/invoices/{id}/charges/{lineId}/reverse")
    @PreAuthorize("hasAuthority('BILLING_WRITE')")
    public InvoiceDetail reverseCharge(@PathVariable UUID id, @PathVariable UUID lineId, @Valid @RequestBody ReasonRequest body) {
        return invoices.reverseCharge(id, lineId, body.reason());
    }

    @Operation(summary = "Post outstanding bed-day room and nursing charges (idempotent)")
    @PostMapping("/invoices/{id}/room-charges")
    @PreAuthorize("hasAuthority('BILLING_WRITE')")
    public RoomChargeResult roomCharges(@PathVariable UUID id) {
        return invoices.postRoomCharges(id);
    }

    @PostMapping("/invoices/{id}/estimates")
    @PreAuthorize("hasAuthority('BILLING_WRITE')")
    public InvoiceDetail addEstimate(@PathVariable UUID id, @Valid @RequestBody EstimateRequest body) {
        return invoices.addEstimate(id, body);
    }

    @PostMapping("/invoices/{id}/estimates/{estimateId}/deactivate")
    @PreAuthorize("hasAuthority('BILLING_WRITE')")
    public InvoiceDetail deactivateEstimate(@PathVariable UUID id, @PathVariable UUID estimateId) {
        return invoices.deactivateEstimate(id, estimateId);
    }

    @Operation(summary = "Finalize: post room charges to date, mark FINALIZED and reviewed")
    @PostMapping("/invoices/{id}/finalize")
    @PreAuthorize("hasAuthority('BILLING_WRITE')")
    public InvoiceDetail finalizeInvoice(@PathVariable UUID id, @Valid @RequestBody(required = false) FinalizeRequest body) {
        return invoices.finalizeInvoice(id, body == null ? null : body.notes());
    }

    @PostMapping("/invoices/{id}/reopen")
    @PreAuthorize("hasAuthority('BILLING_WRITE')")
    public InvoiceDetail reopen(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return invoices.reopen(id, body.reason());
    }

    @Operation(summary = "Itemized invoice statement PDF (staff, or the patient / authorized caregiver)")
    @GetMapping("/invoices/{id}/invoice.pdf")
    @PreAuthorize("hasAnyAuthority('BILLING_READ','PORTAL_ACCESS')")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable UUID id) {
        return pdfResponse(pdf.invoice(id));
    }

    @Operation(summary = "Active tariffs for the charge picker")
    @GetMapping("/billing/tariffs")
    @PreAuthorize("hasAuthority('BILLING_READ')")
    public List<TariffOption> tariffs(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) ServiceTariff.Category category) {
        return invoices.tariffs(q, category);
    }

    static ResponseEntity<byte[]> pdfResponse(BillingPdfService.PdfFile f) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(f.content());
    }
}
