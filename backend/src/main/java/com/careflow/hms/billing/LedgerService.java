package com.careflow.hms.billing;

import com.careflow.hms.billing.LedgerEntry.EntryType;
import com.careflow.hms.common.Money;
import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single authority for invoice balances. Balances are always derived from the append-only
 * ledger, never stored or computed on the client.
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    public enum PaymentStatus { NO_CHARGES, UNPAID, PARTIALLY_PAID, PAID, CREDIT_BALANCE }

    /**
     * Breakdown of an invoice balance. All values are positive magnitudes except {@code balance},
     * which is signed (negative = credit owed to the patient).
     *
     * <pre>
     * balance = grossCharges - chargeReversals + debitNotes + refunds
     *         - (payments - paymentReversals) - discounts - writeOffs - creditNotes - insuranceSettled
     * </pre>
     */
    public record BalanceSummary(
            BigDecimal grossCharges,
            BigDecimal chargeReversals,
            BigDecimal netCharges,
            BigDecimal payments,
            BigDecimal paymentReversals,
            BigDecimal netPayments,
            BigDecimal discounts,
            BigDecimal writeOffs,
            BigDecimal creditNotes,
            BigDecimal debitNotes,
            BigDecimal refunds,
            BigDecimal insuranceSettled,
            BigDecimal insuranceApprovedPending,
            BigDecimal balance,
            BigDecimal estimatedFutureCharges,
            PaymentStatus paymentStatus,
            String formula) {
    }

    private final LedgerEntryRepository ledger;
    private final InsuranceClaimRepository claims;
    private final EstimatedChargeRepository estimates;

    @Transactional
    public LedgerEntry post(Invoice invoice, EntryType type, BigDecimal signedAmount, String referenceType,
                            UUID referenceId, String referenceNumber, String description) {
        LedgerEntry e = new LedgerEntry();
        e.setOrganizationId(invoice.getOrganizationId());
        e.setInvoiceId(invoice.getId());
        e.setPatientId(invoice.getPatientId());
        e.setEntryType(type);
        e.setAmount(Money.scale(signedAmount));
        e.setReferenceType(referenceType);
        e.setReferenceId(referenceId);
        e.setReferenceNumber(referenceNumber);
        e.setDescription(description.length() > 500 ? description.substring(0, 500) : description);
        e.setCreatedBy(TenantContext.actorId());
        e.setCreatedAt(BillingClock.now());
        return ledger.save(e);
    }

    @Transactional(readOnly = true)
    public BigDecimal balance(UUID invoiceId) {
        return Money.scale(ledger.balance(invoiceId));
    }

    @Transactional(readOnly = true)
    public BalanceSummary summarize(UUID invoiceId) {
        List<LedgerEntry> entries = ledger.findByInvoiceIdOrderByCreatedAtAsc(invoiceId);
        Map<EntryType, BigDecimal> sums = new EnumMap<>(EntryType.class);
        for (EntryType t : EntryType.values()) {
            sums.put(t, Money.ZERO);
        }
        BigDecimal balance = Money.ZERO;
        for (LedgerEntry e : entries) {
            sums.merge(e.getEntryType(), e.getAmount().abs(), BigDecimal::add);
            balance = balance.add(e.getAmount());
        }
        BigDecimal gross = Money.scale(sums.get(EntryType.CHARGE));
        BigDecimal chargeRev = Money.scale(sums.get(EntryType.CHARGE_REVERSAL));
        BigDecimal payments = Money.scale(sums.get(EntryType.PAYMENT));
        BigDecimal payRev = Money.scale(sums.get(EntryType.PAYMENT_REVERSAL));
        BigDecimal netCharges = Money.scale(gross.subtract(chargeRev));
        BigDecimal netPayments = Money.scale(payments.subtract(payRev));
        BigDecimal pendingInsurance = claims.findByInvoiceIdOrderByCreatedAtAsc(invoiceId).stream()
                .filter(c -> c.getStatus() == InsuranceClaim.Status.APPROVED
                        || c.getStatus() == InsuranceClaim.Status.PARTIALLY_APPROVED)
                .map(c -> Money.nz(c.getApprovedAmount()).subtract(Money.nz(c.getSettledAmount())).max(BigDecimal.ZERO))
                .reduce(Money.ZERO, BigDecimal::add);
        BigDecimal estimated = estimates.findByInvoiceIdAndActiveTrueOrderByCreatedAtAsc(invoiceId).stream()
                .map(EstimatedCharge::getEstimatedAmount)
                .reduce(Money.ZERO, BigDecimal::add);
        balance = Money.scale(balance);
        BigDecimal credits = netPayments.add(sums.get(EntryType.DISCOUNT)).add(sums.get(EntryType.WRITE_OFF))
                .add(sums.get(EntryType.CREDIT_NOTE)).add(sums.get(EntryType.INSURANCE_SETTLEMENT));
        PaymentStatus status;
        if (netCharges.signum() == 0 && sums.get(EntryType.DEBIT_NOTE).signum() == 0) {
            status = balance.signum() < 0 ? PaymentStatus.CREDIT_BALANCE : PaymentStatus.NO_CHARGES;
        } else if (balance.signum() < 0) {
            status = PaymentStatus.CREDIT_BALANCE;
        } else if (balance.signum() == 0) {
            status = PaymentStatus.PAID;
        } else if (credits.signum() > 0) {
            status = PaymentStatus.PARTIALLY_PAID;
        } else {
            status = PaymentStatus.UNPAID;
        }
        return new BalanceSummary(gross, chargeRev, netCharges, payments, payRev, netPayments,
                Money.scale(sums.get(EntryType.DISCOUNT)), Money.scale(sums.get(EntryType.WRITE_OFF)),
                Money.scale(sums.get(EntryType.CREDIT_NOTE)), Money.scale(sums.get(EntryType.DEBIT_NOTE)),
                Money.scale(sums.get(EntryType.REFUND)), Money.scale(sums.get(EntryType.INSURANCE_SETTLEMENT)),
                Money.scale(pendingInsurance), balance, Money.scale(estimated), status,
                "Outstanding = posted charges - reversals + debit notes + refunds - payments (net of reversals)"
                        + " - discounts - write-offs - credit notes - insurance settlements. Estimates are excluded.");
    }
}
