package com.careflow.hms.pharmacy;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.medication.Medication;
import com.careflow.hms.medication.MedicationOrder;
import com.careflow.hms.medication.MedicationOrderRepository;
import com.careflow.hms.medication.MedicationRepository;
import com.careflow.hms.pharmacy.PharmacyDtos.AdjustDirection;
import com.careflow.hms.pharmacy.PharmacyDtos.AdjustReason;
import com.careflow.hms.pharmacy.PharmacyDtos.AdjustRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.BatchView;
import com.careflow.hms.pharmacy.PharmacyDtos.DispenseRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.DispensationView;
import com.careflow.hms.pharmacy.PharmacyDtos.ReceiveStockRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.ReturnRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.VerifyRequest;
import com.careflow.hms.seed.DemoContext;
import com.careflow.hms.seed.DemoSeedContributor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Demo pharmacy data (fictional): stock batches for the whole catalogue with realistic expiry spread,
 * low-stock, near-expiry, expired-on-hand, written-off and quarantined examples, pharmacist verification of
 * most active prescriptions, backdated dispensing history with pharmacy charges, one partial return and
 * an open query to a prescriber. Everything goes through the real pharmacy services.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PharmacyDemoSeedContributor implements DemoSeedContributor {

    private static final String PHARMACIST = "pharmacist";
    private static final String[] SUPPLIERS = {
            "MediSupply Distributors (demo)", "Sahyadri Pharma Traders (demo)", "Deccan Healthcare Logistics (demo)"};

    private final InventoryService inventory;
    private final PrescriptionQueueService queue;
    private final DispensingService dispensing;
    private final MedicationRepository medications;
    private final MedicationOrderRepository orders;
    private final AdmissionRepository admissions;

    @Override
    public int order() {
        return 200;
    }

    @Override
    public String name() {
        return "Pharmacy inventory, verification and dispensing";
    }

    @Override
    public void seed(DemoContext ctx) {
        ZoneId zone = ZoneId.of(ctx.organization().getTimezone());
        LocalDate today = LocalDate.now(zone);
        Map<String, List<BatchView>> received = new HashMap<>();
        int i = 0;
        for (Map.Entry<String, UUID> e : ctx.medications.entrySet()) {
            String code = e.getKey();
            Medication m = medications.findById(e.getValue()).orElseThrow();
            int r = Math.max(m.getReorderLevel(), 5);
            String supplierA = SUPPLIERS[i % SUPPLIERS.length];
            String supplierB = SUPPLIERS[(i + 1) % SUPPLIERS.length];
            int spread = Math.floorMod(code.hashCode(), 120);
            List<BatchView> list = received.computeIfAbsent(code, k -> new ArrayList<>());
            switch (code) {
                case "MED-MEROP1G" -> list.add(receive(ctx, m, 30, today.plusDays(300), 14, supplierA, "A"));
                case "MED-NORAD4" -> list.add(receive(ctx, m, 45, today.plusDays(240), 6, supplierA, "A"));
                case "MED-BUDNEB" -> list.add(receive(ctx, m, 25, today.plusDays(330), 20, supplierA, "A"));
                case "MED-ADEN6" -> list.add(receive(ctx, m, 90, today.plusDays(200), 4, supplierA, "A"));
                default -> {
                    LocalDate expiryA = switch (code) {
                        case "MED-SALBNEB" -> today.minusDays(5);       // expired, still on the shelf
                        case "MED-ONDAN4" -> today.plusDays(25);        // near expiry
                        case "MED-PANTO40IV" -> today.plusDays(60);     // near expiry
                        default -> today.plusDays(150 + spread);
                    };
                    int receivedAgo = code.equals("MED-SALBNEB") ? 200 : 75;
                    int qtyA = switch (code) {
                        case "MED-SALBNEB" -> 12;
                        case "MED-ONDAN4" -> 30;
                        case "MED-PANTO40IV" -> 24;
                        default -> Math.max(r, 10);
                    };
                    list.add(receive(ctx, m, receivedAgo, expiryA, qtyA, supplierA, "A"));
                    list.add(receive(ctx, m, 12, today.plusDays(420 + spread * 2L), Math.max(2 * r, 20), supplierB, "B"));
                }
            }
            i++;
        }

        // A batch that expired last month and was written off (shows the full lifecycle in the ledger).
        Medication cephalexin = medications.findById(ctx.medications.get("MED-CEPHAL500")).orElseThrow();
        BatchView oldCeph = receive(ctx, cephalexin, 400, today.minusDays(40), 14, SUPPLIERS[2], "X");
        at(ctx, 38, () -> inventory.writeOff(oldCeph.id(), "Expired on shelf; destroyed via biomedical waste vendor (demo)"));

        // A quarantined batch (supplier recall notice under review).
        at(ctx, 3, () -> inventory.quarantine(first(received, "MED-LACT").id(),
                "Supplier recall notice RC-2210 (fictional) for this lot; hold pending confirmation"));
        // Stock adjustments with documented reasons.
        at(ctx, 8, () -> inventory.adjust(second(received, "MED-NS500").id(), new AdjustRequest(AdjustDirection.OUT, 6,
                AdjustReason.DAMAGE, "Six bags punctured during unpacking; disposed per SOP")));
        at(ctx, 5, () -> inventory.adjust(second(received, "MED-PCM500").id(), new AdjustRequest(AdjustDirection.IN, 20,
                AdjustReason.COUNT_CORRECTION, "Unrecorded carton found during weekly cycle count")));
        at(ctx, 2, () -> inventory.adjust(first(received, "MED-ADEN6").id(), new AdjustRequest(AdjustDirection.OUT, 4,
                AdjustReason.COUNT_CORRECTION, "Cycle count found no vials on the shelf; discrepancy escalated to the chief pharmacist")));

        // ---------------------------------------------------------------- verification
        Set<String> leaveUnverified = Set.of("fatima:MED-INSREG", "fatima:MED-NS500", "vikram:MED-ATOR40",
                "aarav:MED-PRED10", "george:MED-ENOX40");
        int verified = 0;
        for (String patientKey : List.of("ravi", "irfan", "ananya", "george", "deepa", "aarav", "vikram", "fatima")) {
            UUID admissionId = ctx.admissions.get(patientKey);
            Optional<Admission> adm = admissionId == null ? Optional.empty() : admissions.findById(admissionId);
            if (adm.isEmpty() || !adm.get().getStatus().isOpen()) {
                continue;
            }
            for (MedicationOrder o : orders.findByAdmissionIdAndStatusIn(admissionId, List.of(MedicationOrder.Status.ACTIVE))) {
                String code = codeOf(ctx, o.getMedicationId());
                if (o.getVerifiedAt() != null || leaveUnverified.contains(patientKey + ":" + code)) {
                    continue;
                }
                Instant when = o.getPrescribedAt().plus(Duration.ofMinutes(55));
                Instant latest = Instant.now().minus(Duration.ofMinutes(10));
                Instant verifyAt = when.isAfter(latest) ? latest : when;
                PharmacyClock.runAt(verifyAt, () -> ctx.actAs(PHARMACIST,
                        () -> queue.verify(o.getId(), new VerifyRequest(true, null))));
                verified++;
            }
        }

        // ---------------------------------------------------------------- dispensing history
        dispense(ctx, "ravi", "MED-CEFTRX1G", 1, 3.0, 2.0, 1.2);
        dispense(ctx, "ravi", "MED-AZI500", 1, 3.0, 2.0, 0.3);
        dispense(ctx, "ravi", "MED-INSREG", 1, 3.5);
        dispense(ctx, "ravi", "MED-PANTO40", 1, 2.0, 0.5);
        dispense(ctx, "ravi", "MED-PCM500", 10, 3.0);
        dispense(ctx, "irfan", "MED-CEFTRX1G", 2, 1.5, 0.4);
        dispense(ctx, "irfan", "MED-TRAM50", 3, 1.4);
        dispense(ctx, "irfan", "MED-ONDAN4", 3, 1.4);
        dispense(ctx, "irfan", "MED-PANTO40IV", 1, 1.8);
        dispense(ctx, "ananya", "MED-METO25", 2, 0.8);
        dispense(ctx, "george", "MED-MEROP1G", 3, 2.0, 1.1);
        dispense(ctx, "george", "MED-NS500", 3, 1.1);
        dispense(ctx, "george", "MED-PANTO40IV", 1, 0.6);
        dispense(ctx, "deepa", "MED-CEFTRX1G", 1, 2.0, 0.9);
        dispense(ctx, "aarav", "MED-SALBNEB", 6, 1.5, 0.5);
        dispense(ctx, "aarav", "MED-BUDNEB", 2, 1.5);
        dispense(ctx, "vikram", "MED-ASP75", 1, 2.5, 0.7);
        dispense(ctx, "vikram", "MED-CLOP75", 1, 2.5);
        dispense(ctx, "vikram", "MED-METO25", 2, 2.5);
        // Partial return: maintenance fluids reduced, one bag came back unused.
        List<DispensationView> fluids = dispense(ctx, "irfan", "MED-NS500", 3, 1.9, 0.9);
        if (fluids.size() == 2) {
            at(ctx, 0.2, () -> dispensing.returnStock(fluids.get(1).id(),
                    new ReturnRequest(1, "Infusion rate reduced by the surgical team; one bag returned unopened", false)));
        }
        // Today's discharge supply for Deepa (live: ward nurses are notified).
        dispenseNow(ctx, "deepa", "MED-CEPHAL500", 28, "7-day discharge course; counselled on completing the course");
        dispenseNow(ctx, "deepa", "MED-PCM500", 10, "Discharge supply; patient advised to avoid NSAIDs");

        // An open query to the prescriber (live notification to the prescribing doctor).
        findOrder(ctx, "george", "MED-ENOX40").ifPresent(o -> ctx.actAs(PHARMACIST, () -> queue.raiseQuery(o.getId(),
                "Patient has CKD (paracetamol capped at 3 g/24 h). Please confirm enoxaparin 40 mg SC OD is appropriate"
                        + " for the current creatinine clearance, or advise a renal dose.")));

        ctx.log.add("Pharmacy: " + received.values().stream().mapToInt(List::size).sum() + " batches received, "
                + verified + " prescriptions verified, dispensing history created");
        log.info("Pharmacy demo data seeded ({} prescriptions verified)", verified);
    }

    // ------------------------------------------------------------------ helpers

    private BatchView receive(DemoContext ctx, Medication m, int receivedDaysAgo, LocalDate expiry, int qty, String supplier,
                              String suffix) {
        Instant at = DemoContext.daysAgo(receivedDaysAgo);
        String yymm = at.atZone(ZoneId.of(ctx.organization().getTimezone())).format(DateTimeFormatter.ofPattern("yyMM"));
        String prefix = m.getCode().replace("MED-", "");
        prefix = prefix.length() > 6 ? prefix.substring(0, 6) : prefix;
        BigDecimal cost = m.getUnitPrice().multiply(new BigDecimal("0.62")).setScale(2, RoundingMode.HALF_UP);
        String batchNumber = prefix + "-" + yymm + suffix;
        return PharmacyClock.runAt(at, () -> ctx.actAs(PHARMACIST, () -> inventory.receive(
                new ReceiveStockRequest(m.getId(), batchNumber, expiry, qty, cost, supplier))));
    }

    private <T> void at(DemoContext ctx, double daysAgo, Supplier<T> work) {
        PharmacyClock.runAt(DemoContext.daysAgo(daysAgo), () -> ctx.actAs(PHARMACIST, work));
    }

    private List<DispensationView> dispense(DemoContext ctx, String patientKey, String code, int qty, double... daysAgo) {
        List<DispensationView> out = new ArrayList<>();
        Optional<MedicationOrder> order = findOrder(ctx, patientKey, code);
        if (order.isEmpty() || order.get().getVerifiedAt() == null) {
            return out;
        }
        for (double d : daysAgo) {
            Instant when = DemoContext.daysAgo(d);
            if (when.isBefore(order.get().getVerifiedAt())) {
                continue;
            }
            out.add(PharmacyClock.runAt(when, () -> ctx.actAs(PHARMACIST, () -> dispensing.dispense(
                    new DispenseRequest(order.get().getId(), null, qty, null, true)))));
        }
        return out;
    }

    private void dispenseNow(DemoContext ctx, String patientKey, String code, int qty, String notes) {
        findOrder(ctx, patientKey, code).filter(o -> o.getVerifiedAt() != null).ifPresent(o -> ctx.actAs(PHARMACIST,
                () -> dispensing.dispense(new DispenseRequest(o.getId(), null, qty, notes, true))));
    }

    private Optional<MedicationOrder> findOrder(DemoContext ctx, String patientKey, String code) {
        UUID admissionId = ctx.admissions.get(patientKey);
        UUID medId = ctx.medications.get(code);
        if (admissionId == null || medId == null) {
            return Optional.empty();
        }
        return orders.findByAdmissionIdAndStatusIn(admissionId, List.of(MedicationOrder.Status.ACTIVE)).stream()
                .filter(o -> o.getMedicationId().equals(medId)).findFirst();
    }

    private static String codeOf(DemoContext ctx, UUID medicationId) {
        return ctx.medications.entrySet().stream().filter(e -> e.getValue().equals(medicationId))
                .map(Map.Entry::getKey).findFirst().orElse("");
    }

    private static BatchView first(Map<String, List<BatchView>> received, String code) {
        return received.get(code).get(0);
    }

    private static BatchView second(Map<String, List<BatchView>> received, String code) {
        List<BatchView> l = received.get(code);
        return l.get(l.size() > 1 ? 1 : 0);
    }
}
