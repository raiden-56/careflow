package com.careflow.hms.billing;

import com.careflow.hms.billing.BillingDtos.AdjustmentRequest;
import com.careflow.hms.billing.BillingDtos.AdjustmentView;
import com.careflow.hms.billing.BillingDtos.DecisionRequest;
import com.careflow.hms.billing.BillingDtos.PaymentResult;
import com.careflow.hms.billing.BillingDtos.PaymentView;
import com.careflow.hms.billing.BillingDtos.ReasonRequest;
import com.careflow.hms.billing.BillingDtos.RecordPaymentRequest;
import com.careflow.hms.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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
import java.util.Set;
import java.util.UUID;

@Tag(name = "Billing - payments, receipts and adjustments")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService payments;
    private final AdjustmentService adjustments;
    private final BillingPdfService pdf;

    @Operation(summary = "Record a payment. Idempotent per idempotencyKey: a retry returns the original payment (200).")
    @PostMapping("/invoices/{id}/payments")
    @PreAuthorize("hasAuthority('PAYMENT_RECORD')")
    public ResponseEntity<PaymentResult> record(@PathVariable UUID id, @Valid @RequestBody RecordPaymentRequest body) {
        PaymentResult result;
        try {
            result = payments.record(id, body);
        } catch (DataIntegrityViolationException e) {
            // Last line of defence: a concurrent duplicate won the unique (organization, idempotency key) race.
            result = payments.findReplay(id, body.idempotencyKey()).orElseThrow(() -> e);
        }
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    @Operation(summary = "Reverse a payment, e.g. a bounced cheque (PAYMENT_REVERSAL ledger entry)")
    @PostMapping("/payments/{id}/reverse")
    @PreAuthorize("hasAuthority('PAYMENT_RECORD')")
    public PaymentView reverse(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return payments.reverse(id, body.reason());
    }

    @GetMapping("/payments")
    @PreAuthorize("hasAuthority('BILLING_READ')")
    public PageResponse<PaymentView> search(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(required = false) Set<Payment.Method> method,
                                            @RequestParam(required = false) Set<Payment.Status> status,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) String sort) {
        return payments.search(from, to, method, status, q, page, size, sort);
    }

    @GetMapping("/payments/{id}")
    @PreAuthorize("hasAuthority('BILLING_READ')")
    public PaymentView get(@PathVariable UUID id) {
        return payments.get(id);
    }

    @Operation(summary = "Payment receipt PDF (staff, or the patient / authorized caregiver)")
    @GetMapping("/payments/{id}/receipt.pdf")
    @PreAuthorize("hasAnyAuthority('BILLING_READ','PORTAL_ACCESS')")
    public ResponseEntity<byte[]> receipt(@PathVariable UUID id) {
        return InvoiceController.pdfResponse(pdf.receipt(id));
    }

    // ------------------------------------------------------------------ adjustments

    @Operation(summary = "Request a discount, write-off, credit/debit note or refund (posts only when approved)")
    @PostMapping("/invoices/{id}/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ADJUSTMENT_REQUEST')")
    public AdjustmentView requestAdjustment(@PathVariable UUID id, @Valid @RequestBody AdjustmentRequest body) {
        return adjustments.request(id, body);
    }

    @Operation(summary = "Approve an adjustment (approver must differ from the requester)")
    @PostMapping("/adjustments/{id}/approve")
    @PreAuthorize("hasAuthority('ADJUSTMENT_APPROVE')")
    public AdjustmentView approve(@PathVariable UUID id, @Valid @RequestBody(required = false) DecisionRequest body) {
        return adjustments.approve(id, body == null ? null : body.notes());
    }

    @PostMapping("/adjustments/{id}/reject")
    @PreAuthorize("hasAuthority('ADJUSTMENT_APPROVE')")
    public AdjustmentView reject(@PathVariable UUID id, @Valid @RequestBody DecisionRequest body) {
        return adjustments.reject(id, body.notes());
    }

    @GetMapping("/adjustments")
    @PreAuthorize("hasAuthority('BILLING_READ')")
    public PageResponse<AdjustmentView> listAdjustments(@RequestParam(required = false) Set<FinancialAdjustment.Status> status,
                                                        @RequestParam(required = false) Set<FinancialAdjustment.Type> type,
                                                        @RequestParam(required = false) String q,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        @RequestParam(required = false) String sort) {
        return adjustments.search(status, type, q, page, size, sort);
    }
}
