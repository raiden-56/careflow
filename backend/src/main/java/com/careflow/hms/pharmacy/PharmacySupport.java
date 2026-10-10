package com.careflow.hms.pharmacy;

import com.careflow.hms.admission.AdmissionDtos.AdmissionSummary;
import com.careflow.hms.common.Money;
import com.careflow.hms.medication.Medication;
import com.careflow.hms.medication.MedicationRepository;
import com.careflow.hms.patient.Allergy;
import com.careflow.hms.pharmacy.PharmacyDtos.Availability;
import com.careflow.hms.pharmacy.PharmacyDtos.BatchView;
import com.careflow.hms.pharmacy.PharmacyDtos.MedicationView;
import com.careflow.hms.pharmacy.PharmacyDtos.MovementView;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Shared lookups and mappings for the pharmacy services. */
@Component
@RequiredArgsConstructor
public class PharmacySupport {

    /** Inpatient supply cycle: scheduled orders are re-supplied once per cycle. */
    public static final int SUPPLY_CYCLE_HOURS = 24;
    public static final int DEFAULT_NEAR_EXPIRY_DAYS = 90;
    /** Open-ended date-range bounds (PostgreSQL cannot type null timestamp parameters in JPQL). */
    public static final java.time.Instant MIN_INSTANT = java.time.Instant.parse("1970-01-01T00:00:00Z");
    public static final java.time.Instant MAX_INSTANT = java.time.Instant.parse("9999-12-31T00:00:00Z");

    private final InventoryBatchRepository batches;
    private final MedicationRepository medications;
    private final UserRepository users;

    public static Availability availability(long usable, int reorderLevel) {
        if (usable <= 0) {
            return Availability.OUT_OF_STOCK;
        }
        return usable < reorderLevel ? Availability.LOW_STOCK : Availability.IN_STOCK;
    }

    /** Usable stock (ACTIVE, unexpired) per medication. Missing ids have zero stock. */
    public Map<UUID, Long> usableStock(Collection<UUID> medicationIds) {
        Map<UUID, Long> result = new HashMap<>();
        if (medicationIds.isEmpty()) {
            return result;
        }
        for (Object[] row : batches.usableStockByMedication(medicationIds, PharmacyClock.today())) {
            result.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }

    public long usableStock(UUID medicationId) {
        return batches.usableStock(medicationId, PharmacyClock.today());
    }

    public Map<UUID, Medication> medicationsById(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        return medications.findByIdIn(new HashSet<>(ids)).stream()
                .collect(Collectors.toMap(Medication::getId, Function.identity()));
    }

    public Map<UUID, String> userNames(Collection<UUID> ids) {
        Set<UUID> clean = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (clean.isEmpty()) {
            return new HashMap<>();
        }
        return users.findAllById(clean).stream().collect(Collectors.toMap(User::getId, User::getFullName));
    }

    public static MedicationView toView(Medication m, long stock) {
        return new MedicationView(m.getId(), m.getCode(), m.getGenericName(), m.getBrandName(), m.getForm(),
                m.getStrength(), m.getDefaultRoute(), m.getDrugClass(), m.getAllergenGroup(), m.isHighAlert(),
                m.isControlled(), Money.scale(m.getUnitPrice()), m.getDispenseUnit(), m.getReorderLevel(), m.isActive(),
                stock, availability(stock, m.getReorderLevel()), m.displayName(), m.getVersion());
    }

    public static BatchView toBatchView(InventoryBatch b, Medication m, String receivedByName, LocalDate today) {
        boolean expired = b.getExpiryDate().isBefore(today);
        long days = ChronoUnit.DAYS.between(today, b.getExpiryDate());
        boolean usable = b.getStatus() == InventoryBatch.Status.ACTIVE && !expired && b.getQuantityOnHand() > 0;
        BigDecimal value = Money.multiply(Money.nz(b.getUnitCost()), BigDecimal.valueOf(b.getQuantityOnHand()));
        return new BatchView(b.getId(), b.getMedicationId(), m == null ? null : m.getCode(),
                m == null ? null : m.displayName(), m == null ? null : m.getDispenseUnit(), b.getBatchNumber(),
                b.getExpiryDate(), days, expired, b.getQuantityReceived(), b.getQuantityOnHand(),
                Money.scale(b.getUnitCost()), value, b.getSupplier(), b.getReceivedAt(), receivedByName, b.getStatus(),
                usable, b.getVersion());
    }

    public List<BatchView> toBatchViews(List<InventoryBatch> list) {
        Map<UUID, Medication> meds = medicationsById(list.stream().map(InventoryBatch::getMedicationId).toList());
        Map<UUID, String> names = userNames(list.stream().map(InventoryBatch::getReceivedBy).toList());
        LocalDate today = PharmacyClock.today();
        return list.stream().map(b -> toBatchView(b, meds.get(b.getMedicationId()), names.get(b.getReceivedBy()), today)).toList();
    }

    public List<MovementView> toMovementViews(List<StockMovement> list) {
        Map<UUID, Medication> meds = medicationsById(list.stream().map(StockMovement::getMedicationId).toList());
        Map<UUID, String> names = userNames(list.stream().map(StockMovement::getPerformedBy).toList());
        Map<UUID, String> batchNumbers = new HashMap<>();
        batches.findAllById(list.stream().map(StockMovement::getBatchId).collect(Collectors.toSet()))
                .forEach(b -> batchNumbers.put(b.getId(), b.getBatchNumber()));
        return list.stream().map(mv -> {
            Medication m = meds.get(mv.getMedicationId());
            return new MovementView(mv.getId(), mv.getBatchId(), batchNumbers.get(mv.getBatchId()), mv.getMedicationId(),
                    m == null ? null : m.displayName(), mv.getMovementType(), mv.getQuantity(), mv.getBalanceAfter(),
                    mv.getReferenceType(), mv.getReferenceId(), mv.getReason(), names.get(mv.getPerformedBy()),
                    mv.getPerformedAt());
        }).toList();
    }

    public static String location(AdmissionSummary a) {
        if (a == null || a.location() == null) {
            return "Awaiting bed";
        }
        return a.location().wardName() + " · Rm " + a.location().roomNumber() + " · Bed " + a.location().bedNumber();
    }

    // ------------------------------------------------------------------ allergy matching

    private static final Set<String> STOPWORDS = Set.of("drug", "drugs", "allergy", "allergic", "with", "from", "and",
            "type", "demo", "other", "unknown", "agent", "agents", "medication", "medicine", "class", "group");

    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) {
            return out;
        }
        for (String raw : s.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (raw.length() < 4 || STOPWORDS.contains(raw)) {
                continue;
            }
            out.add(raw.length() > 4 && raw.endsWith("s") ? raw.substring(0, raw.length() - 1) : raw);
        }
        return out;
    }

    /**
     * True when a documented drug allergy names this medication, its allergen group or its drug class
     * (word match, e.g. "Penicillin" ~ allergen group PENICILLIN, "Ibuprofen (NSAIDs)" ~ NSAID). This is a
     * transparent text match against the documented allergy - not a clinical cross-reactivity database.
     */
    public static boolean allergyMatches(Allergy a, Medication m) {
        if (a.getAllergyType() != Allergy.AllergyType.DRUG && a.getAllergyType() != Allergy.AllergyType.OTHER) {
            return false;
        }
        Set<String> allergyTokens = tokens(a.getAllergen());
        if (allergyTokens.isEmpty()) {
            return false;
        }
        Set<String> medTokens = new HashSet<>();
        medTokens.addAll(tokens(m.getAllergenGroup()));
        medTokens.addAll(tokens(m.getGenericName()));
        medTokens.addAll(tokens(m.getBrandName()));
        medTokens.addAll(tokens(m.getDrugClass()));
        allergyTokens.retainAll(medTokens);
        return !allergyTokens.isEmpty();
    }

    public static String allergyLabel(Allergy a) {
        return a.getAllergen() + " (" + a.getSeverity().name().toLowerCase(Locale.ROOT).replace('_', '-') + ")";
    }

    public static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) {
            s = "'" + s; // neutralize spreadsheet formula injection
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
