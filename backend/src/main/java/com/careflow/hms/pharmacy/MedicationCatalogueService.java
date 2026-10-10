package com.careflow.hms.pharmacy;

import com.careflow.hms.audit.AuditService;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.Money;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.medication.Medication;
import com.careflow.hms.medication.MedicationRepository;
import com.careflow.hms.pharmacy.PharmacyDtos.Availability;
import com.careflow.hms.pharmacy.PharmacyDtos.MedicationRequest;
import com.careflow.hms.pharmacy.PharmacyDtos.MedicationView;
import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Medication catalogue (pharmacy-managed) with live stock availability for prescribers and pharmacy. */
@Service
@RequiredArgsConstructor
public class MedicationCatalogueService {

    private static final Map<String, String> SORTS = Map.of(
            "code", "code", "genericName", "genericName", "form", "form", "drugClass", "drugClass",
            "unitPrice", "unitPrice", "reorderLevel", "reorderLevel", "stockOnHand", "stockOnHand");

    private final MedicationRepository medications;
    private final PharmacySupport support;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public PageResponse<MedicationView> search(String q, Boolean active, Medication.Form form, String drugClass,
                                               Boolean highAlert, Boolean controlled, Availability availability,
                                               int page, int size, String sort) {
        UUID orgId = TenantContext.requireOrganizationId();
        String cls = drugClass == null || drugClass.isBlank() ? null : drugClass.trim().toLowerCase(Locale.ROOT);
        String like = Paging.likePattern(q);
        Pageable pageable = Paging.of(page, size, sort, SORTS, Sort.by("genericName", "strength"));
        Sort.Order stockOrder = pageable.getSort().getOrderFor("stockOnHand");
        if (availability == null && stockOrder == null) {
            Page<Medication> result = medications.searchCatalogue(orgId, active, form, cls, highAlert, controlled, like, pageable);
            Map<UUID, Long> stock = support.usableStock(result.getContent().stream().map(Medication::getId).toList());
            return PageResponse.of(result, m -> PharmacySupport.toView(m, stock.getOrDefault(m.getId(), 0L)));
        }
        // Availability is derived from batches: filter/sort in memory (catalogues are small).
        Sort dbSort = stockOrder != null ? Sort.by("genericName", "strength") : pageable.getSort();
        List<Medication> all = medications.searchCatalogueAll(orgId, active, form, cls, highAlert, controlled, like, dbSort);
        Map<UUID, Long> stock = support.usableStock(all.stream().map(Medication::getId).toList());
        List<MedicationView> views = all.stream()
                .map(m -> PharmacySupport.toView(m, stock.getOrDefault(m.getId(), 0L)))
                .filter(v -> availability == null || v.availability() == availability)
                .toList();
        if (stockOrder != null) {
            Comparator<MedicationView> c = Comparator.comparingLong(MedicationView::stockOnHand);
            views = views.stream().sorted(stockOrder.isAscending() ? c : c.reversed()).toList();
        }
        return PageResponse.ofList(views, pageable.getPageNumber(), pageable.getPageSize());
    }

    @Transactional(readOnly = true)
    public MedicationView get(UUID id) {
        Medication m = require(id);
        return PharmacySupport.toView(m, support.usableStock(m.getId()));
    }

    @Transactional(readOnly = true)
    public List<String> drugClasses() {
        return medications.drugClasses(TenantContext.requireOrganizationId());
    }

    @Transactional
    public MedicationView create(MedicationRequest r) {
        UUID orgId = TenantContext.requireOrganizationId();
        String code = r.code().trim().toUpperCase(Locale.ROOT);
        if (medications.existsByOrganizationIdAndCode(orgId, code)) {
            throw ApiException.conflict("DUPLICATE_CODE", "A medication with code " + code + " already exists.");
        }
        Medication m = new Medication();
        m.setCode(code);
        apply(m, r);
        m = medications.saveAndFlush(m);
        audit.record("MEDICATION_CREATED", "MEDICATION", m.getId(), null, code + " " + m.displayName());
        return PharmacySupport.toView(m, 0);
    }

    @Transactional
    public MedicationView update(UUID id, MedicationRequest r) {
        Medication m = require(id);
        if (r.version() == null || !r.version().equals(m.getVersion())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "This medication was changed by someone else. Reload and try again.");
        }
        String code = r.code().trim().toUpperCase(Locale.ROOT);
        if (!code.equals(m.getCode())) {
            throw ApiException.badRequest("CODE_IMMUTABLE", "The medication code cannot be changed once created.");
        }
        boolean priceChanged = Money.scale(r.unitPrice()).compareTo(Money.scale(m.getUnitPrice())) != 0;
        String before = m.displayName() + " @ " + Money.scale(m.getUnitPrice());
        apply(m, r);
        m = medications.saveAndFlush(m);
        audit.record("MEDICATION_UPDATED", "MEDICATION", m.getId(), null,
                code + " updated" + (priceChanged ? " (price " + before + " -> " + Money.scale(m.getUnitPrice()) + ")" : ""));
        return PharmacySupport.toView(m, support.usableStock(m.getId()));
    }

    @Transactional
    public MedicationView setActive(UUID id, boolean active, String reason) {
        Medication m = require(id);
        if (m.isActive() == active) {
            throw ApiException.rule(active ? "ALREADY_ACTIVE" : "ALREADY_INACTIVE",
                    m.getCode() + " is already " + (active ? "active" : "inactive") + ".");
        }
        m.setActive(active);
        m = medications.saveAndFlush(m);
        audit.record(active ? "MEDICATION_ACTIVATED" : "MEDICATION_DEACTIVATED", "MEDICATION", m.getId(), null,
                m.getCode() + ": " + reason);
        return PharmacySupport.toView(m, support.usableStock(m.getId()));
    }

    Medication require(UUID id) {
        return medications.findByIdAndOrganizationId(id, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Medication"));
    }

    private static void apply(Medication m, MedicationRequest r) {
        m.setGenericName(r.genericName().trim());
        m.setBrandName(blankToNull(r.brandName()));
        m.setForm(r.form());
        m.setStrength(r.strength().trim());
        m.setDefaultRoute(r.defaultRoute());
        m.setDrugClass(blankToNull(r.drugClass()));
        String group = blankToNull(r.allergenGroup());
        m.setAllergenGroup(group == null ? null : group.toUpperCase(Locale.ROOT));
        m.setHighAlert(r.isHighAlert());
        m.setControlled(r.isControlled());
        m.setUnitPrice(Money.scale(r.unitPrice()));
        m.setDispenseUnit(r.dispenseUnit().trim().toUpperCase(Locale.ROOT));
        m.setReorderLevel(r.reorderLevel());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
