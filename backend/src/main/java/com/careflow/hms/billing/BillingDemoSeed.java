package com.careflow.hms.billing;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.billing.BillingDtos.AdjustmentRequest;
import com.careflow.hms.billing.BillingDtos.AdjustmentView;
import com.careflow.hms.billing.BillingDtos.ClaimApproveRequest;
import com.careflow.hms.billing.BillingDtos.ClaimRequest;
import com.careflow.hms.billing.BillingDtos.ClaimSettleRequest;
import com.careflow.hms.billing.BillingDtos.ClaimView;
import com.careflow.hms.billing.BillingDtos.EstimateRequest;
import com.careflow.hms.billing.BillingDtos.PaymentResult;
import com.careflow.hms.billing.BillingDtos.RaiseRequest;
import com.careflow.hms.billing.BillingDtos.RecordPaymentRequest;
import com.careflow.hms.billing.BillingDtos.RequestView;
import com.careflow.hms.billing.BillingDtos.RespondRequest;
import com.careflow.hms.seed.DemoContext;
import com.careflow.hms.seed.DemoSeedContributor;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Billing demo data (fictional): itemized charges, nightly room/nursing charges, estimates, payments
 * with realistic receipts, adjustments (one pending discount, one approved refund), insurance claims
 * in different stages and patient billing queries. Everything is created through the real billing
 * services "as of" historical instants (see {@link BillingClock}) so the append-only ledger carries
 * correct timestamps. Amounts that must close a bill are computed from the ledger at seeding time,
 * so the scenario stays consistent even when other modules have already posted charges.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingDemoSeed implements DemoSeedContributor {

    private final AdmissionRepository admissions;
    private final InvoiceRepository invoices;
    private final InvoiceLineItemRepository lines;
    private final ChargeService charges;
    private final RoomChargeService roomCharges;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final AdjustmentService adjustmentService;
    private final ClaimService claimService;
    private final BillingRequestService requestService;
    private final LedgerService ledger;
    private final JdbcTemplate jdbc;
    private final EntityManager em;

    private ZoneId zone;
    private int keySeq;

    @Override
    public int order() {
        return 300;
    }

    @Override
    public String name() {
        return "Billing: charges, payments, adjustments, claims, queries";
    }

    @Override
    public void seed(DemoContext ctx) {
        zone = ctx.organization().zone();
        String[] all = {"ravi", "irfan", "ananya", "george", "deepa", "aarav", "vikram", "fatima", "lakshmi", "rohan", "sanjay"};

        // ---- Phase 1: admission & registration fee for every billed stay.
        for (String key : all) {
            charge(ctx, key, "ADMISSION_FEE", 1, admitted(ctx, key).plus(Duration.ofMinutes(20)));
        }

        // ---- Phase 2: itemized charges + nightly room/nursing charges. Vikram and Aarav paid an advance
        //      deposit at admission, so their stay charges are posted after the deposits (phase 4).
        for (String key : all) {
            if (!key.equals("vikram") && !key.equals("aarav")) {
                accrue(ctx, key);
            }
        }
        // Ravi: a duplicate CBC was posted and reversed (correction trail visible on the bill).
        Instant raviIn = admitted(ctx, "ravi");
        InvoiceLineItem duplicate = charge(ctx, "ravi", "LAB_CBC", 1, raviIn.plus(Duration.ofHours(26)));
        ctx.actAs("billing_lead", () -> BillingClock.at(raviIn.plus(Duration.ofHours(30)), () ->
                invoiceService.reverseCharge(invoice(ctx, "ravi").getId(), duplicate.getId(),
                        "Duplicate entry - CBC for this sample was already billed on admission")));
        // Discharged stays get their final bill at discharge.
        Instant lakIn = admitted(ctx, "lakshmi");
        Instant lakOut = discharged(ctx, "lakshmi");
        Instant rohOut = discharged(ctx, "rohan");
        Instant sanIn = admitted(ctx, "sanjay");
        Instant sanOut = discharged(ctx, "sanjay");
        finalize(ctx, "rohan", rohOut.minus(Duration.ofHours(1)));
        finalize(ctx, "lakshmi", lakOut.minus(Duration.ofHours(2)));
        finalize(ctx, "sanjay", sanOut.minus(Duration.ofHours(2)));

        // ---- Phase 3: payments, recorded in chronological order so receipt numbers follow time.
        BigDecimal rohBill = balance(ctx, "rohan");
        BigDecimal lakBill = balance(ctx, "lakshmi");
        BigDecimal sanBill = balance(ctx, "sanjay");
        BigDecimal raviBal = balance(ctx, "ravi");
        BigDecimal sanClaimed = roundDown(sanBill.multiply(new BigDecimal("0.90")), 1_000);
        BigDecimal sanApproved = sanClaimed.subtract(money(5_000)).max(money(1_000));
        Instant vikramIn = admitted(ctx, "vikram");
        Instant aaravIn = admitted(ctx, "aarav");
        java.util.Map<String, PaymentResult> paid = new java.util.HashMap<>();
        java.util.List<PlannedPayment> plan = new java.util.ArrayList<>(java.util.List.of(
                new PlannedPayment("rohan-cash", "billing", "rohan", roundDown(rohBill.multiply(new BigDecimal("0.30")), 1_000),
                        Payment.Method.CASH, null, "Meena Desai", false, "Part payment at discharge; balance promised within two weeks",
                        rohOut.minus(Duration.ofMinutes(30))),
                new PlannedPayment("rohan-cheque", "billing", "rohan", roundDown(rohBill.multiply(new BigDecimal("0.20")), 1_000),
                        Payment.Method.CHEQUE, "CHQ 004512 / Demo Co-operative Bank", "Meena Desai", false, null, rohOut.plus(Duration.ofDays(2))),
                new PlannedPayment("rohan-upi", "billing", "rohan", roundDown(rohBill.multiply(new BigDecimal("0.15")), 1_000),
                        Payment.Method.UPI, "UPI 7730 1185 2290", "Rohan Desai", false, null, rohOut.plus(Duration.ofDays(8))),
                new PlannedPayment("lakshmi-deposit", "billing", "lakshmi", roundUp(lakBill.add(money(4_000)), 5_000),
                        Payment.Method.CARD, "POS-AUTH 210377", "Hari Pillai", true, "Advance deposit at admission",
                        lakIn.plus(Duration.ofHours(1))),
                new PlannedPayment("vikram-deposit", "billing", "vikram", money(10_000), Payment.Method.CARD, "POS-AUTH 448210",
                        "Vikram Singh", true, "Advance deposit at admission", vikramIn.plus(Duration.ofMinutes(50))),
                new PlannedPayment("aarav-deposit", "billing", "aarav", money(5_000), Payment.Method.UPI, "UPI 6021 4410 7782",
                        "Kiran Reddy", true, "Advance deposit by mother", aaravIn.plus(Duration.ofMinutes(45))),
                new PlannedPayment("ravi-upi", "billing", "ravi", roundDown(raviBal.multiply(new BigDecimal("0.40")), 500),
                        Payment.Method.UPI, "UPI 4512 8830 1194", "Sunita Sharma", false, null, hoursAgo(50)),
                new PlannedPayment("ravi-cash", "billing_lead", "ravi", roundDown(raviBal.multiply(new BigDecimal("0.25")), 500),
                        Payment.Method.CASH, null, "Ravi Sharma", false, "Paid at the billing desk", hoursAgo(22))));
        BigDecimal sanShare = sanBill.subtract(sanApproved);
        if (sanShare.signum() > 0) {
            plan.add(new PlannedPayment("sanjay-share", "billing", "sanjay", sanShare, Payment.Method.CARD, "POS-AUTH 993104",
                    "Kavita Patel", false, "Patient share after insurance approval", sanOut.minus(Duration.ofMinutes(40))));
        }
        plan.sort(java.util.Comparator.comparing(PlannedPayment::at));
        for (PlannedPayment pp : plan) {
            paid.put(pp.label(), pay(ctx, pp.user(), pp.key(), pp.amount(), pp.method(), pp.reference(), pp.payer(),
                    pp.allowAdvance(), pp.notes(), pp.at()));
        }

        // ---- Phase 4: Vikram's and Aarav's stay charges (after their advance deposits).
        accrue(ctx, "vikram");
        accrue(ctx, "aarav");

        // ---- Phase 5: reversals, adjustments, claims and queries (chronological where numbered).
        ctx.actAs("billing_lead", () -> BillingClock.at(rohOut.plus(Duration.ofDays(5)), () ->
                paymentService.reverse(paid.get("rohan-cheque").payment().id(), "Cheque returned unpaid by the bank (insufficient funds)")));

        // Lakshmi: the unused part of the advance deposit is refunded after the final bill -> PAID.
        BigDecimal credit = balance(ctx, "lakshmi").negate();
        if (credit.signum() > 0) {
            AdjustmentView refund = ctx.actAs("billing", () -> BillingClock.at(lakOut.minus(Duration.ofMinutes(50)), () ->
                    adjustmentService.request(invoice(ctx, "lakshmi").getId(), new AdjustmentRequest(FinancialAdjustment.Type.REFUND,
                            credit, "Refund of unused advance deposit after final bill", paid.get("lakshmi-deposit").payment().id(),
                            Payment.Method.NET_BANKING))));
            ctx.actAs("billing_lead", () -> BillingClock.at(lakOut.minus(Duration.ofMinutes(20)), () ->
                    adjustmentService.approve(refund.id(), "Verified against the final bill; NEFT to the payer's account")));
        }
        RequestView lakReq = ctx.actAs("p_lakshmi", () -> BillingClock.at(lakOut.plus(Duration.ofHours(20)), () ->
                requestService.raise(invoice(ctx, "lakshmi").getId(), new RaiseRequest(BillingRequest.RequestType.ITEMIZATION,
                        "Itemized bill for my insurance reimbursement",
                        "Please share the itemized bill with the investigation details so I can file a reimbursement claim.", null))));
        ctx.actAs("billing", () -> BillingClock.at(lakOut.plus(Duration.ofHours(27)), () ->
                requestService.respond(lakReq.id(), new RespondRequest(BillingRequest.Status.RESOLVED,
                        "The itemized statement is available under My bills - use 'Download itemized bill (PDF)'. "
                                + "It lists every investigation with its date and amount."))));

        // Sanjay: TPA claim partially approved, settled in two tranches; with the patient's share the bill is PAID.
        ClaimView sanClaim = ctx.actAs("billing", () -> BillingClock.at(sanIn.plus(Duration.ofHours(2)), () ->
                claimService.create(invoice(ctx, "sanjay").getId(), new ClaimRequest("CareShield TPA Services (demo) for Navjeevan General Insurance",
                        InsuranceClaim.PayerType.TPA, "NGI-FL-4410982", "PA-2026-54871", sanClaimed,
                        "Planned laparoscopic cholecystectomy; cashless"))));
        ctx.actAs("billing", () -> BillingClock.at(sanIn.plus(Duration.ofHours(3)), () -> claimService.submit(sanClaim.id())));
        // Vikram: insurer approved in full, settlement pending.
        ClaimView vikramClaim = ctx.actAs("billing", () -> BillingClock.at(vikramIn.plus(Duration.ofHours(2)), () ->
                claimService.create(invoice(ctx, "vikram").getId(), new ClaimRequest("Navjeevan General Insurance (fictional)",
                        InsuranceClaim.PayerType.INSURER, "NGI-HL-7781203", "PA-2026-55190", money(25_000),
                        "Cashless pre-authorisation for elective coronary angiography"))));
        ctx.actAs("billing", () -> BillingClock.at(vikramIn.plus(Duration.ofHours(2).plusMinutes(30)), () -> claimService.submit(vikramClaim.id())));
        // Irfan: employer (corporate) claim submitted, awaiting a decision.
        Instant irfanIn = admitted(ctx, "irfan");
        ClaimView irfanClaim = ctx.actAs("billing", () -> BillingClock.at(irfanIn.plus(Duration.ofHours(3)), () ->
                claimService.create(invoice(ctx, "irfan").getId(), new ClaimRequest("Deccan Softworks Pvt Ltd (fictional employer)",
                        InsuranceClaim.PayerType.CORPORATE, "EMP-DSW-20417", null, money(60_000),
                        "Employer medical cover; HR confirmation awaited"))));
        ctx.actAs("billing", () -> BillingClock.at(irfanIn.plus(Duration.ofHours(5)), () -> claimService.submit(irfanClaim.id())));
        ctx.actAs("billing", () -> BillingClock.at(sanIn.plus(Duration.ofHours(30)), () -> claimService.markUnderReview(sanClaim.id())));
        ctx.actAs("billing", () -> BillingClock.at(sanOut.minus(Duration.ofHours(4)), () -> claimService.approve(sanClaim.id(),
                new ClaimApproveRequest(sanApproved, "Non-medical consumables and the room-rent difference are not payable under the policy"))));
        ctx.actAs("billing", () -> BillingClock.at(vikramIn.plus(Duration.ofHours(20)), () -> claimService.markUnderReview(vikramClaim.id())));
        ctx.actAs("billing", () -> BillingClock.at(vikramIn.plus(Duration.ofHours(30)), () ->
                claimService.approve(vikramClaim.id(), new ClaimApproveRequest(money(25_000), "Approved in full under the cashless facility"))));
        BigDecimal part1 = roundDown(sanApproved.multiply(new BigDecimal("0.60")), 1_000);
        ctx.actAs("billing", () -> BillingClock.at(sanOut.plus(Duration.ofHours(6)), () -> claimService.settle(sanClaim.id(),
                new ClaimSettleRequest(part1, "NEFT UTR NGI26100481", null, "First tranche"))));
        ctx.actAs("billing", () -> BillingClock.at(hoursAgo(10), () -> claimService.settle(sanClaim.id(),
                new ClaimSettleRequest(sanApproved.subtract(part1), "NEFT UTR NGI26100977", null, "Final tranche"))));

        // George: hardship discount requested by Suresh, awaiting a second person's approval.
        BigDecimal georgeRoom = lines.findByInvoiceIdOrderByServiceDateAscPostedAtAsc(invoice(ctx, "george").getId()).stream()
                .filter(l -> l.getCategory() == ServiceTariff.Category.ROOM && l.getStatus() == InvoiceLineItem.Status.POSTED)
                .map(InvoiceLineItem::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal georgeDiscount = roundDown(georgeRoom.multiply(new BigDecimal("0.10")), 100);
        if (georgeDiscount.signum() > 0) {
            ctx.actAs("billing", () -> BillingClock.at(hoursAgo(3), () -> adjustmentService.request(invoice(ctx, "george").getId(),
                    new AdjustmentRequest(FinancialAdjustment.Type.DISCOUNT, georgeDiscount,
                            "Financial hardship concession (10% of ICU room charges) recommended by the medical social worker",
                            null, null))));
        }

        // Ravi's caregiver asks about the daily visit charges (open query).
        InvoiceLineItem consult = lines.findByInvoiceIdOrderByServiceDateAscPostedAtAsc(invoice(ctx, "ravi").getId()).stream()
                .filter(l -> l.getCategory() == ServiceTariff.Category.CONSULTATION && l.getStatus() == InvoiceLineItem.Status.POSTED)
                .findFirst().orElse(null);
        ctx.actAs("cg_sunita", () -> BillingClock.at(hoursAgo(9), () -> requestService.raise(invoice(ctx, "ravi").getId(),
                new RaiseRequest(BillingRequest.RequestType.QUERY, "Daily doctor visit charges",
                        "Dr. Mehta saw Ravi once a day, but I would like to confirm how the daily visit charges are calculated "
                                + "and whether the specialist review on day 2 is billed separately.",
                        consult == null ? null : consult.getId()))));

        // Estimates for ongoing stays (never part of the balance).
        estimate(ctx, "ravi", ServiceTariff.Category.ROOM, "General ward bed and nursing, 2 more days", 4_200,
                "Expected discharge in 2-3 days if fever settles");
        estimate(ctx, "ravi", ServiceTariff.Category.PHARMACY, "Discharge medicines (oral antibiotics, inhalers)", 1_800,
                "Typical 7-day discharge prescription");
        estimate(ctx, "irfan", ServiceTariff.Category.ROOM, "Post-operative stay, 2 days general ward with nursing", 4_200,
                "Usual recovery after laparoscopic appendectomy");
        estimate(ctx, "irfan", ServiceTariff.Category.PROCEDURE, "Follow-up wound dressing", 400, "One dressing before discharge");
        estimate(ctx, "ananya", ServiceTariff.Category.ROOM, "Semi-private room and nursing, 2 more days", 7_200,
                "Rhythm monitoring until the electrophysiology review");
        estimate(ctx, "ananya", ServiceTariff.Category.CONSULTATION, "Electrophysiology specialist consultation", 1_200,
                "Planned review");
        estimate(ctx, "george", ServiceTariff.Category.ROOM, "ICU bed and nursing, 4 more days", 50_400,
                "Expected ICU course for urosepsis with AKI; may step down earlier");
        estimate(ctx, "george", ServiceTariff.Category.LAB, "Repeat cultures and renal panels", 2_650, "Every 48 hours while in ICU");
        estimate(ctx, "aarav", ServiceTariff.Category.ROOM, "Paediatric ward bed and nursing, 2 more days", 4_200,
                "Until nebulisation can be spaced to 4-hourly");
        estimate(ctx, "vikram", ServiceTariff.Category.ROOM, "Private room and nursing, 1 more day", 6_375, "Observation after angiography");
        estimate(ctx, "vikram", ServiceTariff.Category.PROCEDURE, "Coronary angioplasty with one drug-eluting stent (only if indicated)",
                185_000, "Depends on angiography findings; discussed with the family");
        estimate(ctx, "fatima", ServiceTariff.Category.ROOM, "General ward bed and nursing, 3 days", 6_300,
                "Expected stay to stabilise blood sugars");
        estimate(ctx, "fatima", ServiceTariff.Category.OTHER, "Diabetes education and dietitian sessions", 500, "Before discharge");
        estimate(ctx, "deepa", ServiceTariff.Category.PHARMACY, "Discharge medicines (oral antibiotics for 5 days)", 900,
                "Planned step-down from IV antibiotics");

        em.flush();
        backdateSideEffects(ctx.orgId());
        ctx.log.add("billing seeded");
        log.info("Billing demo data seeded");
    }

    private record PlannedPayment(String label, String user, String key, BigDecimal amount, Payment.Method method,
                                  String reference, String payer, boolean allowAdvance, String notes, Instant at) {
    }

    /** Itemized charges, then the nightly room + nursing charges for every completed day of the stay. */
    private void accrue(DemoContext ctx, String key) {
        itemized(ctx, key);
        ctx.actAs("billing", () -> {
            Invoice inv = invoices.lockById(invoice(ctx, key).getId(), ctx.orgId()).orElseThrow();
            roomCharges.post(inv, false, true);
        });
    }
    // ------------------------------------------------------------------ itemized charges

    private void itemized(DemoContext ctx, String key) {
        switch (key) {
            case "ravi" -> {
                Instant ravi = admitted(ctx, "ravi");
                charge(ctx, "ravi", "LAB_CBC", 1, ravi.plus(Duration.ofHours(1)));
                charge(ctx, "ravi", "LAB_CRP", 1, ravi.plus(Duration.ofHours(1)));
                charge(ctx, "ravi", "IMG_CXR", 1, ravi.plus(Duration.ofHours(2)));
                charge(ctx, "ravi", "LAB_BCX", 1, ravi.plus(Duration.ofHours(2)));
                charge(ctx, "ravi", "CONSUMABLE_IV_SET", 3, ravi.plus(Duration.ofHours(3)));
                for (int d = 0; d < 4; d++) {
                    charge(ctx, "ravi", "CONS_DAILY", 1, ravi.plus(Duration.ofHours(6 + 24L * d)));
                }
                charge(ctx, "ravi", "CONS_SPECIALIST", 1, ravi.plus(Duration.ofHours(30)));
            }
            case "irfan" -> {
                Instant irfan = admitted(ctx, "irfan");
                charge(ctx, "irfan", "LAB_CBC", 1, irfan.plus(Duration.ofHours(1)));
                charge(ctx, "irfan", "IMG_USG_ABD", 1, irfan.plus(Duration.ofHours(2)));
                charge(ctx, "irfan", "CONS_SPECIALIST", 1, irfan.plus(Duration.ofHours(2)));
                charge(ctx, "irfan", "PROC_APPENDECTOMY", 1, irfan.plus(Duration.ofHours(9)));
                charge(ctx, "irfan", "CONSUMABLE_IV_SET", 2, irfan.plus(Duration.ofHours(10)));
                charge(ctx, "irfan", "CONS_DAILY", 1, irfan.plus(Duration.ofHours(26)));
            }
            case "ananya" -> {
                Instant ananya = admitted(ctx, "ananya");
                charge(ctx, "ananya", "IMG_ECG", 1, ananya.plus(Duration.ofMinutes(40)));
                charge(ctx, "ananya", "LAB_TROP", 1, ananya.plus(Duration.ofHours(1)));
                charge(ctx, "ananya", "LAB_ELEC", 1, ananya.plus(Duration.ofHours(1)));
                charge(ctx, "ananya", "IMG_ECHO", 1, ananya.plus(Duration.ofHours(5)));
                charge(ctx, "ananya", "CONS_SPECIALIST", 1, ananya.plus(Duration.ofHours(6)));
            }
            case "george" -> {
                Instant george = admitted(ctx, "george");
                charge(ctx, "george", "LAB_BCX", 1, george.plus(Duration.ofHours(1)));
                charge(ctx, "george", "LAB_UCX", 1, george.plus(Duration.ofHours(1)));
                charge(ctx, "george", "LAB_RFT", 1, george.plus(Duration.ofHours(1)));
                charge(ctx, "george", "LAB_ELEC", 1, george.plus(Duration.ofHours(1)));
                charge(ctx, "george", "PROC_CVC", 1, george.plus(Duration.ofHours(3)));
                charge(ctx, "george", "CONSUMABLE_IV_SET", 6, george.plus(Duration.ofHours(4)));
                for (int d = 1; d < 6; d++) {
                    charge(ctx, "george", "CONS_SPECIALIST", 1, george.plus(Duration.ofHours(8 + 24L * d)));
                    if (d % 2 == 0) {
                        charge(ctx, "george", "LAB_RFT", 1, george.plus(Duration.ofHours(7 + 24L * d)));
                        charge(ctx, "george", "LAB_ELEC", 1, george.plus(Duration.ofHours(7 + 24L * d)));
                    }
                }
            }
            case "deepa" -> {
                Instant deepa = admitted(ctx, "deepa");
                charge(ctx, "deepa", "LAB_CBC", 1, deepa.plus(Duration.ofHours(1)));
                charge(ctx, "deepa", "LAB_CRP", 1, deepa.plus(Duration.ofHours(1)));
                charge(ctx, "deepa", "CONSUMABLE_IV_SET", 4, deepa.plus(Duration.ofHours(2)));
                for (int d = 0; d < 5; d++) {
                    charge(ctx, "deepa", "CONS_DAILY", 1, deepa.plus(Duration.ofHours(5 + 24L * d)));
                    if (d % 2 == 1) {
                        charge(ctx, "deepa", "PROC_DRESSING", 1, deepa.plus(Duration.ofHours(10 + 24L * d)));
                    }
                }
            }
            case "aarav" -> {
                Instant aarav = admitted(ctx, "aarav");
                charge(ctx, "aarav", "LAB_CBC", 1, aarav.plus(Duration.ofHours(1)));
                charge(ctx, "aarav", "IMG_CXR", 1, aarav.plus(Duration.ofHours(2)));
                charge(ctx, "aarav", "CONS_DAILY", 1, aarav.plus(Duration.ofHours(3)));
                charge(ctx, "aarav", "CONS_DAILY", 1, aarav.plus(Duration.ofHours(27)));
            }
            case "vikram" -> {
                Instant vikram = admitted(ctx, "vikram");
                charge(ctx, "vikram", "IMG_ECG", 1, vikram.plus(Duration.ofHours(1)));
                charge(ctx, "vikram", "LAB_LIPID", 1, vikram.plus(Duration.ofHours(1)));
                charge(ctx, "vikram", "LAB_RFT", 1, vikram.plus(Duration.ofHours(1)));
                charge(ctx, "vikram", "CONS_SPECIALIST", 1, vikram.plus(Duration.ofHours(4)));
                charge(ctx, "vikram", "PROC_ANGIO", 1, vikram.plus(Duration.ofHours(26)));
                charge(ctx, "vikram", "LAB_TROP", 1, vikram.plus(Duration.ofHours(32)));
            }
            case "fatima" -> {
                Instant fatima = admitted(ctx, "fatima");
                charge(ctx, "fatima", "LAB_HBA1C", 1, fatima.plus(Duration.ofMinutes(50)));
                charge(ctx, "fatima", "LAB_ELEC", 1, fatima.plus(Duration.ofMinutes(55)));
            }
            case "lakshmi" -> {
                Instant lak = admitted(ctx, "lakshmi");
                charge(ctx, "lakshmi", "IMG_ECG", 1, lak.plus(Duration.ofMinutes(40)));
                charge(ctx, "lakshmi", "LAB_TROP", 1, lak.plus(Duration.ofHours(1)));
                charge(ctx, "lakshmi", "LAB_RFT", 1, lak.plus(Duration.ofHours(1)));
                charge(ctx, "lakshmi", "LAB_ELEC", 1, lak.plus(Duration.ofHours(1)));
                charge(ctx, "lakshmi", "IMG_CXR", 1, lak.plus(Duration.ofHours(2)));
                charge(ctx, "lakshmi", "IMG_ECHO", 1, lak.plus(Duration.ofHours(20)));
                charge(ctx, "lakshmi", "LAB_ELEC", 1, lak.plus(Duration.ofHours(50)));
                for (int d = 0; d < 3; d++) {
                    charge(ctx, "lakshmi", "CONS_SPECIALIST", 1, lak.plus(Duration.ofHours(6 + 36L * d)));
                }
            }
            case "rohan" -> {
                Instant rohan = admitted(ctx, "rohan");
                charge(ctx, "rohan", "LAB_CBC", 1, rohan.plus(Duration.ofHours(1)));
                charge(ctx, "rohan", "CONS_SPECIALIST", 1, rohan.plus(Duration.ofHours(2)));
                charge(ctx, "rohan", "PROC_ORIF", 1, rohan.plus(Duration.ofHours(20)));
                charge(ctx, "rohan", "CONSUMABLE_IV_SET", 2, rohan.plus(Duration.ofHours(21)));
                charge(ctx, "rohan", "CONS_SPECIALIST", 1, rohan.plus(Duration.ofHours(44)));
                charge(ctx, "rohan", "PROC_DRESSING", 1, rohan.plus(Duration.ofHours(68)));
                charge(ctx, "rohan", "PROC_DRESSING", 1, rohan.plus(Duration.ofHours(90)));
            }
            case "sanjay" -> {
                Instant sanjay = admitted(ctx, "sanjay");
                charge(ctx, "sanjay", "LAB_CBC", 1, sanjay.plus(Duration.ofHours(1)));
                charge(ctx, "sanjay", "LAB_LFT", 1, sanjay.plus(Duration.ofHours(1)));
                charge(ctx, "sanjay", "IMG_USG_ABD", 1, sanjay.plus(Duration.ofHours(2)));
                charge(ctx, "sanjay", "CONS_SPECIALIST", 1, sanjay.plus(Duration.ofHours(3)));
                charge(ctx, "sanjay", "PROC_LAP_CHOLE", 1, sanjay.plus(Duration.ofHours(22)));
                charge(ctx, "sanjay", "CONSUMABLE_IV_SET", 2, sanjay.plus(Duration.ofHours(23)));
                charge(ctx, "sanjay", "CONS_SPECIALIST", 1, sanjay.plus(Duration.ofHours(48)));
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private InvoiceLineItem charge(DemoContext ctx, String key, String tariff, int qty, Instant at) {
        Instant when = clampToStay(ctx, key, at);
        LocalDate serviceDate = when.atZone(zone).toLocalDate();
        return ctx.actAs("billing", () -> BillingClock.at(when, () -> charges.postCharge(ChargeService.ChargeRequest.builder()
                .invoiceId(invoice(ctx, key).getId()).tariffId(ctx.tariff(tariff)).quantity(BigDecimal.valueOf(qty))
                .serviceDate(serviceDate).sourceType(InvoiceLineItem.SourceType.MANUAL).build())));
    }

    private PaymentResult pay(DemoContext ctx, String user, String key, BigDecimal amount, Payment.Method method, String ref,
                              String payer, boolean allowAdvance, String notes, Instant at) {
        Instant when = at.isAfter(Instant.now()) ? Instant.now().minusSeconds(300) : at;
        String idem = "demo-seed-" + key + "-" + (++keySeq) + "-" + UUID.randomUUID();
        return ctx.actAs(user, () -> BillingClock.at(when, () -> paymentService.record(invoice(ctx, key).getId(),
                new RecordPaymentRequest(amount, method, ref, payer, idem, allowAdvance, notes))));
    }

    private void estimate(DemoContext ctx, String key, ServiceTariff.Category cat, String description, long amount, String basis) {
        Instant when = clampToStay(ctx, key, admitted(ctx, key).plus(Duration.ofHours(4)));
        ctx.actAs("billing", () -> BillingClock.at(when, () -> invoiceService.addEstimate(invoice(ctx, key).getId(),
                new EstimateRequest(cat, description, money(amount), basis))));
    }

    private void finalize(DemoContext ctx, String key, Instant at) {
        ctx.actAs("billing_lead", () -> BillingClock.at(at, () -> invoiceService.finalizeInvoice(invoice(ctx, key).getId(),
                "Final bill reviewed with the family at discharge")));
    }

    private Invoice invoice(DemoContext ctx, String key) {
        return invoices.findActiveByAdmission(ctx.admission(key)).orElseThrow();
    }

    private BigDecimal balance(DemoContext ctx, String key) {
        em.flush();
        return ledger.balance(invoice(ctx, key).getId());
    }

    private Instant admitted(DemoContext ctx, String key) {
        return admissions.findById(ctx.admission(key)).map(Admission::getAdmittedAt).orElseThrow();
    }

    private Instant discharged(DemoContext ctx, String key) {
        return admissions.findById(ctx.admission(key)).map(Admission::getDischargedAt).orElseThrow();
    }

    /** Keeps seeded events inside the stay and in the past. */
    private Instant clampToStay(DemoContext ctx, String key, Instant at) {
        Admission a = admissions.findById(ctx.admission(key)).orElseThrow();
        Instant latest = a.getDischargedAt() != null ? a.getDischargedAt().minus(Duration.ofMinutes(45)) : Instant.now().minusSeconds(600);
        Instant result = at.isAfter(latest) ? latest : at;
        return result.isBefore(a.getAdmittedAt()) ? a.getAdmittedAt().plusSeconds(60) : result;
    }

    private static Instant hoursAgo(double h) {
        return DemoContext.hoursAgo(h);
    }

    private static BigDecimal money(long rupees) {
        return BigDecimal.valueOf(rupees).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal roundDown(BigDecimal v, long step) {
        BigDecimal s = BigDecimal.valueOf(step);
        BigDecimal r = v.divide(s, 0, RoundingMode.FLOOR).multiply(s);
        return (r.signum() <= 0 ? s : r).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal roundUp(BigDecimal v, long step) {
        BigDecimal s = BigDecimal.valueOf(step);
        return v.divide(s, 0, RoundingMode.CEILING).multiply(s).setScale(2, RoundingMode.HALF_UP);
    }

    /** Aligns notification and claim timestamps created by the services with the seeded history. */
    private void backdateSideEffects(UUID orgId) {
        jdbc.update("""
                UPDATE notifications n SET created_at = p.received_at,
                       read_at = CASE WHEN p.received_at < now() - interval '1 day' THEN p.received_at + interval '3 hours' END
                FROM payments p WHERE n.organization_id = ? AND n.entity_type = 'PAYMENT' AND n.entity_id = p.id
                  AND n.dedupe_key = 'PAYMENT:' || p.id
                """, orgId);
        jdbc.update("""
                UPDATE notifications n SET created_at = p.reversed_at, read_at = p.reversed_at + interval '5 hours'
                FROM payments p WHERE n.organization_id = ? AND n.entity_type = 'PAYMENT' AND n.entity_id = p.id
                  AND n.dedupe_key = 'PAYMENT_REVERSED:' || p.id
                """, orgId);
        jdbc.update("""
                UPDATE notifications n SET created_at = COALESCE(a.decided_at, a.requested_at),
                       read_at = CASE WHEN COALESCE(a.decided_at, a.requested_at) < now() - interval '1 day'
                                      THEN COALESCE(a.decided_at, a.requested_at) + interval '2 hours' END
                FROM financial_adjustments a
                WHERE n.organization_id = ? AND n.dedupe_key IN ('ADJUSTMENT_DECIDED:' || a.id, 'ADJUSTMENT_PATIENT:' || a.id)
                """, orgId);
        jdbc.update("""
                UPDATE notifications n SET created_at = a.requested_at
                FROM financial_adjustments a WHERE n.organization_id = ? AND n.dedupe_key = 'ADJUSTMENT_REQUESTED:' || a.id
                """, orgId);
        jdbc.update("""
                UPDATE notifications n SET created_at = i.finalized_at, read_at = i.finalized_at + interval '4 hours'
                FROM invoices i WHERE n.organization_id = ? AND n.entity_type = 'INVOICE' AND n.entity_id = i.id
                  AND n.dedupe_key LIKE 'INVOICE_FINALIZED:%' AND i.finalized_at IS NOT NULL
                """, orgId);
        jdbc.update("""
                UPDATE notifications n SET created_at = c.decided_at,
                       read_at = CASE WHEN c.decided_at < now() - interval '1 day' THEN c.decided_at + interval '3 hours' END
                FROM insurance_claims c WHERE n.organization_id = ? AND n.dedupe_key = 'CLAIM_APPROVED:' || c.id
                """, orgId);
        jdbc.update("""
                UPDATE notifications n SET created_at = s.settled_at
                FROM claim_settlements s WHERE n.organization_id = ? AND n.dedupe_key = 'CLAIM_SETTLED:' || s.id
                """, orgId);
        jdbc.update("""
                UPDATE notifications n SET created_at = COALESCE(r.responded_at, r.raised_at),
                       read_at = CASE WHEN r.status IN ('RESOLVED','REJECTED') THEN r.responded_at + interval '1 hour' END
                FROM billing_requests r WHERE n.organization_id = ? AND n.entity_type = 'BILLING_REQUEST' AND n.entity_id = r.id
                """, orgId);
        jdbc.update("""
                UPDATE insurance_claims SET updated_at = COALESCE(settled_at, decided_at, submitted_at, created_at)
                WHERE organization_id = ?
                """, orgId);
    }
}
