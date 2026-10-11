package com.careflow.hms.billing;

import com.careflow.hms.admission.Admission;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request/response records for the billing & accounts module. Money is always BigDecimal (JSON string). */
public final class BillingDtos {

    private BillingDtos() {
    }

    // ------------------------------------------------------------------ shared references

    public record PatientRef(UUID id, String mrn, String fullName, Integer age, String gender, String phone) {
    }

    public record AdmissionRef(UUID id, String admissionNumber, Admission.Status status, Instant admittedAt,
                               Instant dischargedAt, UUID departmentId, String departmentName,
                               String attendingDoctorName, String location) {
    }

    // ------------------------------------------------------------------ invoices

    /** Invoice list row. Balance / payment status are derived from the ledger by the database query. */
    public record InvoiceSummary(UUID id, String invoiceNumber, Invoice.Status status, Invoice.PayerType payerType,
                                 Instant createdAt, Instant finalizedAt, UUID patientId, String patientName,
                                 String mrn, UUID admissionId, String admissionNumber, String admissionStatus,
                                 Instant admittedAt, Instant dischargedAt, String departmentName,
                                 BigDecimal netCharges, BigDecimal credits, BigDecimal netPayments,
                                 BigDecimal balance, LedgerService.PaymentStatus paymentStatus,
                                 Instant lastPaymentAt) {
    }

    public record LineView(UUID id, UUID tariffId, String tariffCode, String category, String description,
                           LocalDate serviceDate, BigDecimal quantity, BigDecimal unitPrice, BigDecimal taxRate,
                           BigDecimal netAmount, BigDecimal taxAmount, BigDecimal totalAmount,
                           InvoiceLineItem.SourceType sourceType, InvoiceLineItem.Status status,
                           UUID reversesLineId, UUID reversedByLineId, String reason, String postedByName,
                           Instant postedAt) {
    }

    public record EstimateView(UUID id, String category, String description, BigDecimal estimatedAmount, String basis,
                               boolean active, String createdByName, Instant createdAt) {
    }

    public record PaymentView(UUID id, UUID invoiceId, String invoiceNumber, UUID patientId, String patientName,
                              String mrn, String receiptNumber, BigDecimal amount, Payment.Method method,
                              String methodReference, String payerName, Payment.Status status, boolean advance,
                              String notes, String receivedByName, Instant receivedAt, String reversedByName,
                              Instant reversedAt, String reversalReason, BigDecimal refundedAmount) {
    }

    public record AdjustmentView(UUID id, UUID invoiceId, String invoiceNumber, UUID patientId, String patientName,
                                 String mrn, String adjustmentNumber, FinancialAdjustment.Type type,
                                 BigDecimal amount, int balanceSign, String reason, UUID relatedPaymentId,
                                 String relatedReceiptNumber, String refundMethod, FinancialAdjustment.Status status,
                                 UUID requestedById, String requestedByName, Instant requestedAt,
                                 String decidedByName, Instant decidedAt, String decisionNotes, Long version) {
    }

    public record SettlementView(UUID id, BigDecimal amount, String reference, String notes, Instant settledAt,
                                 String recordedByName) {
    }

    public record ClaimView(UUID id, UUID invoiceId, String invoiceNumber, UUID patientId, String patientName,
                            String mrn, String claimNumber, String payerName, InsuranceClaim.PayerType payerType,
                            String policyNumber, String preAuthNumber, BigDecimal claimedAmount,
                            BigDecimal approvedAmount, BigDecimal settledAmount, BigDecimal pendingSettlement,
                            InsuranceClaim.Status status, Instant submittedAt, Instant decidedAt, Instant settledAt,
                            String rejectionReason, String notes, String createdByName, Instant createdAt,
                            Instant updatedAt, List<SettlementView> settlements, Long version) {
    }

    public record LedgerView(UUID id, LedgerEntry.EntryType entryType, BigDecimal amount, BigDecimal runningBalance,
                             String referenceType, UUID referenceId, String referenceNumber, String description,
                             String createdByName, Instant createdAt) {
    }

    public record RequestView(UUID id, UUID invoiceId, String invoiceNumber, UUID patientId, String patientName,
                              String mrn, UUID lineItemId, String lineItemDescription,
                              BillingRequest.RequestType requestType, String subject, String description,
                              BillingRequest.Status status, String raisedByName, Instant raisedAt, String response,
                              String respondedByName, Instant respondedAt, Long version) {
    }

    public record CategoryTotal(String category, BigDecimal amount, int lineCount) {
    }

    public record InvoiceDetail(UUID id, String invoiceNumber, Invoice.Status status, Invoice.PayerType payerType,
                                String notes, Instant createdAt, String createdByName, Instant finalizedAt,
                                String finalizedByName, Instant reviewedAt, String reviewedByName,
                                Instant reopenedAt, String reopenedByName, String reopenReason,
                                LocalDate roomChargesPostedUntil, Long version, PatientRef patient,
                                AdmissionRef admission, LedgerService.BalanceSummary summary,
                                List<CategoryTotal> categoryTotals, List<LineView> lines,
                                List<EstimateView> estimates, List<PaymentView> payments,
                                List<AdjustmentView> adjustments, List<ClaimView> claims,
                                List<LedgerView> ledger, List<RequestView> requests,
                                BigDecimal refundableAmount, BigDecimal pendingAdjustmentsTotal) {
    }

    public record TariffOption(UUID id, String code, String name, ServiceTariff.Category category,
                               BigDecimal unitPrice, BigDecimal taxRate, ServiceTariff.BillingUnit billingUnit,
                               String roomType) {
    }

    public record CreateInvoiceRequest(@NotNull UUID admissionId) {
    }

    public record AddChargeRequest(
            @NotNull UUID tariffId,
            @NotNull @DecimalMin(value = "0.01", message = "Quantity must be greater than zero")
            @DecimalMax(value = "9999", message = "Quantity is too large")
            @Digits(integer = 6, fraction = 2) BigDecimal quantity,
            LocalDate serviceDate,
            @DecimalMin(value = "0.00", message = "Price cannot be negative")
            @Digits(integer = 10, fraction = 2, message = "Use at most 2 decimal places") BigDecimal unitPriceOverride,
            @Size(max = 500) String overrideReason,
            @Size(max = 200) String note) {
    }

    public record ReasonRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    public record EstimateRequest(
            @NotNull ServiceTariff.Category category,
            @NotBlank @Size(max = 500) String description,
            @NotNull @DecimalMin(value = "0.00") @Digits(integer = 12, fraction = 2) BigDecimal estimatedAmount,
            @NotBlank @Size(max = 500) String basis) {
    }

    public record FinalizeRequest(@Size(max = 1000) String notes) {
    }

    public record RoomChargeResult(int roomDaysPosted, int nursingDaysPosted, BigDecimal amountPosted,
                                   LocalDate postedThrough, List<String> warnings) {
    }

    // ------------------------------------------------------------------ payments

    public record RecordPaymentRequest(
            @NotNull @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
            @Digits(integer = 12, fraction = 2, message = "Use at most 2 decimal places") BigDecimal amount,
            @NotNull Payment.Method method,
            @Size(max = 100) String methodReference,
            @NotBlank @Size(max = 200) String payerName,
            @NotBlank @Size(min = 8, max = 100) String idempotencyKey,
            boolean allowAdvance,
            @Size(max = 500) String notes) {
    }

    public record PaymentResult(PaymentView payment, boolean replayed, LedgerService.BalanceSummary summary) {
    }

    // ------------------------------------------------------------------ adjustments

    public record AdjustmentRequest(
            @NotNull FinancialAdjustment.Type type,
            @NotNull @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
            @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotBlank @Size(min = 3, max = 1000) String reason,
            UUID relatedPaymentId,
            Payment.Method refundMethod) {
    }

    public record DecisionRequest(@Size(max = 1000) String notes) {
    }

    // ------------------------------------------------------------------ claims

    public record ClaimRequest(
            @NotBlank @Size(max = 200) String payerName,
            @NotNull InsuranceClaim.PayerType payerType,
            @NotBlank @Size(max = 100) String policyNumber,
            @Size(max = 100) String preAuthNumber,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) BigDecimal claimedAmount,
            @Size(max = 1000) String notes) {
    }

    public record ClaimApproveRequest(
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) BigDecimal approvedAmount,
            @Size(max = 1000) String notes) {
    }

    public record ClaimSettleRequest(
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 100) String reference,
            Instant settledAt,
            @Size(max = 500) String notes) {
    }

    // ------------------------------------------------------------------ billing requests

    public record RaiseRequest(
            @NotNull BillingRequest.RequestType requestType,
            @NotBlank @Size(max = 300) String subject,
            @NotBlank @Size(max = 2000) String description,
            UUID lineItemId) {
    }

    public record RespondRequest(@NotNull BillingRequest.Status status, @Size(max = 2000) String response) {
    }

    // ------------------------------------------------------------------ outstanding & reports

    public record OutstandingRow(InvoiceSummary invoice, LocalDate referenceDate, long ageDays, String bucket,
                                 boolean overdue) {
    }

    public record AgingBucket(String bucket, long count, BigDecimal amount) {
    }

    public record OutstandingReport(List<AgingBucket> buckets, BigDecimal totalOutstanding, long invoiceCount,
                                    long overdueCount, BigDecimal overdueAmount,
                                    com.careflow.hms.common.PageResponse<OutstandingRow> rows) {
    }

    public record DashboardStats(BigDecimal todaysCollections, long todaysPaymentCount, BigDecimal outstandingTotal,
                                 long outstandingCount, long pendingApprovals, BigDecimal pendingApprovalAmount,
                                 long openClaims, BigDecimal claimsPendingSettlement, long unpaidDischargedCount,
                                 BigDecimal unpaidDischargedAmount, long openRequests, long openInvoices,
                                 List<DailyAmount> collectionsTrend, List<PaymentView> recentPayments,
                                 List<AgingBucket> aging) {
    }

    public record DailyAmount(LocalDate date, BigDecimal amount) {
    }

    // ------------------------------------------------------------------ portal

    public record ExplanationLine(String label, BigDecimal amount, String operator, String hint) {
    }

    public record PortalBillCard(UUID invoiceId, String invoiceNumber, Invoice.Status status, String admissionNumber,
                                 Admission.Status admissionStatus, Instant admittedAt, Instant dischargedAt,
                                 String departmentName, BigDecimal totalCharges, BigDecimal paid,
                                 BigDecimal insuranceSettled, BigDecimal insurancePending, BigDecimal adjustments,
                                 BigDecimal refunds, BigDecimal outstanding, LedgerService.PaymentStatus paymentStatus,
                                 BigDecimal estimatedFutureCharges, BigDecimal estimatedOutstanding,
                                 Instant finalizedAt) {
    }

    public record PortalLine(UUID id, String description, LocalDate serviceDate, BigDecimal quantity,
                             BigDecimal unitPrice, BigDecimal taxAmount, BigDecimal totalAmount, boolean reversed) {
    }

    public record PortalCategory(String category, BigDecimal total, List<PortalLine> items) {
    }

    public record PortalPayment(UUID id, UUID invoiceId, String invoiceNumber, String patientName,
                                String receiptNumber, BigDecimal amount, Payment.Method method, String payerName,
                                Payment.Status status, boolean advance, Instant receivedAt, Instant reversedAt) {
    }

    public record PortalAdjustment(String adjustmentNumber, FinancialAdjustment.Type type, String label,
                                   BigDecimal amount, int balanceSign, Instant approvedAt) {
    }

    public record PortalClaim(String claimNumber, String payerName, InsuranceClaim.Status status,
                              BigDecimal claimedAmount, BigDecimal approvedAmount, BigDecimal settledAmount,
                              BigDecimal pendingSettlement, Instant submittedAt, String rejectionReason) {
    }

    public record PortalEstimate(String category, String description, BigDecimal estimatedAmount, String basis) {
    }

    public record PortalRequest(UUID id, BillingRequest.RequestType requestType, String subject, String description,
                                String lineItemDescription, BillingRequest.Status status, Instant raisedAt,
                                String raisedByName, String response, Instant respondedAt) {
    }

    public record PortalInvoice(UUID id, String invoiceNumber, Invoice.Status status, Instant createdAt,
                                Instant finalizedAt, String hospitalName, PatientRef patient, AdmissionRef admission,
                                LedgerService.BalanceSummary summary, List<ExplanationLine> explanation,
                                List<String> explanationNotes, List<PortalCategory> categories,
                                List<PortalPayment> payments, List<PortalAdjustment> adjustments,
                                List<PortalClaim> insurance, List<PortalEstimate> estimates,
                                List<PortalRequest> requests) {
    }
}
