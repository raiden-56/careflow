package com.careflow.hms.admin;

import com.careflow.hms.admin.ConfigDtos.TariffRequest;
import com.careflow.hms.admin.ConfigDtos.TariffView;
import com.careflow.hms.audit.AuditEvent;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.billing.ServiceTariff;
import com.careflow.hms.billing.ServiceTariffRepository;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.Money;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.common.Paging;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.facility.Department;
import com.careflow.hms.facility.DepartmentRepository;
import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service and room tariffs. Codes are unique per hospital; tariffs are deactivated, never deleted
 * (posted charges reference them); every price change is audited with old and new values.
 */
@Service
@RequiredArgsConstructor
public class TariffService {

    private static final Map<String, String> SORTS = Map.of("code", "code", "name", "name", "category", "category",
            "unitPrice", "unitPrice", "updatedAt", "updatedAt");

    private final ServiceTariffRepository tariffs;
    private final DepartmentRepository departments;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public PageResponse<TariffView> search(ServiceTariff.Category category, boolean includeInactive, String q, int page,
                                           int size, String sort) {
        UUID org = TenantContext.requireOrganizationId();
        Map<UUID, String> deptNames = departments.findByOrganizationIdOrderByName(org).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        var result = tariffs.search(org, category, !includeInactive, Paging.likePattern(q),
                Paging.of(page, size, sort, SORTS, Sort.by("category").and(Sort.by("code"))));
        return PageResponse.of(result, t -> view(t, deptNames));
    }

    @Transactional
    public TariffView create(TariffRequest r) {
        UUID org = TenantContext.requireOrganizationId();
        validate(r, org);
        if (tariffs.existsByOrganizationIdAndCode(org, r.code())) {
            throw ApiException.conflict("CODE_IN_USE", "Tariff code " + r.code() + " is already used.");
        }
        ServiceTariff t = new ServiceTariff();
        apply(t, r);
        t.setCode(r.code());
        t = tariffs.saveAndFlush(t);
        audit.record("TARIFF_CREATED", "TARIFF", t.getId(), null, AuditEvent.Outcome.SUCCESS,
                t.getCode() + " " + t.getName() + " at " + t.getUnitPrice().toPlainString(),
                Map.of("unitPrice", t.getUnitPrice().toPlainString(), "taxRate", t.getTaxRate().toPlainString()));
        return view(t, null);
    }

    @Transactional
    public TariffView update(UUID id, TariffRequest r) {
        UUID org = TenantContext.requireOrganizationId();
        ServiceTariff t = tariffs.findByIdAndOrganizationId(id, org).orElseThrow(() -> ApiException.notFound("Tariff"));
        if (!Objects.equals(t.getVersion(), r.version())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "This tariff was changed by someone else. Reload and try again.");
        }
        if (!t.getCode().equals(r.code())) {
            throw ApiException.rule("CODE_IMMUTABLE", "Tariff codes cannot be changed because posted charges reference them. Create a new tariff instead.");
        }
        validate(r, org);
        BigDecimal oldPrice = t.getUnitPrice();
        BigDecimal oldTax = t.getTaxRate();
        Map<String, Object> changes = new LinkedHashMap<>();
        diff(changes, "name", t.getName(), r.name().trim());
        diff(changes, "category", t.getCategory(), r.category());
        diff(changes, "billingUnit", t.getBillingUnit(), r.billingUnit());
        diff(changes, "roomType", t.getRoomType(), HospitalSettingsService.blankToNull(r.roomType()));
        apply(t, r);
        boolean priceChanged = oldPrice.compareTo(t.getUnitPrice()) != 0 || oldTax.compareTo(t.getTaxRate()) != 0;
        t = tariffs.saveAndFlush(t);
        if (priceChanged) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("code", t.getCode());
            details.put("oldUnitPrice", oldPrice.toPlainString());
            details.put("newUnitPrice", t.getUnitPrice().toPlainString());
            details.put("oldTaxRate", oldTax.toPlainString());
            details.put("newTaxRate", t.getTaxRate().toPlainString());
            details.put("effectiveFrom", String.valueOf(t.getEffectiveFrom()));
            audit.record("TARIFF_PRICE_CHANGED", "TARIFF", t.getId(), null, AuditEvent.Outcome.SUCCESS,
                    t.getCode() + " price " + oldPrice.toPlainString() + " -> " + t.getUnitPrice().toPlainString()
                            + ", tax " + oldTax.toPlainString() + "% -> " + t.getTaxRate().toPlainString() + "%", details);
        }
        if (!changes.isEmpty()) {
            audit.record("TARIFF_UPDATED", "TARIFF", t.getId(), null, AuditEvent.Outcome.SUCCESS, t.getCode() + " details updated", changes);
        }
        return view(t, null);
    }

    @Transactional
    public TariffView setActive(UUID id, boolean active, String reason) {
        UUID org = TenantContext.requireOrganizationId();
        ServiceTariff t = tariffs.findByIdAndOrganizationId(id, org).orElseThrow(() -> ApiException.notFound("Tariff"));
        if (t.isActive() == active) {
            return view(t, null);
        }
        t.setActive(active);
        t = tariffs.saveAndFlush(t);
        audit.record(active ? "TARIFF_REACTIVATED" : "TARIFF_DEACTIVATED", "TARIFF", t.getId(), null, AuditEvent.Outcome.SUCCESS,
                t.getCode() + (reason == null || reason.isBlank() ? "" : ": " + reason.trim()), null);
        return view(t, null);
    }

    private void validate(TariffRequest r, UUID org) {
        String roomType = HospitalSettingsService.blankToNull(r.roomType());
        if (r.category() == ServiceTariff.Category.ROOM) {
            if (roomType == null) {
                throw ApiException.badRequest("ROOM_TYPE_REQUIRED", "Room tariffs must specify the room type they apply to.");
            }
            if (r.billingUnit() != ServiceTariff.BillingUnit.PER_DAY && r.billingUnit() != ServiceTariff.BillingUnit.PER_HOUR) {
                throw ApiException.badRequest("INVALID_BILLING_UNIT", "Room tariffs are charged per day or per hour.");
            }
        } else if (roomType != null) {
            throw ApiException.badRequest("ROOM_TYPE_NOT_ALLOWED", "Only room tariffs have a room type.");
        }
        if (r.unitPrice().signum() < 0) {
            throw ApiException.badRequest("INVALID_PRICE", "Price must be zero or more.");
        }
        if (r.taxRate().signum() < 0 || r.taxRate().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.badRequest("INVALID_TAX", "Tax rate must be between 0 and 100.");
        }
        if (r.departmentId() != null) {
            departments.findByIdAndOrganizationId(r.departmentId(), org).orElseThrow(() -> ApiException.notFound("Department"));
        }
    }

    private void apply(ServiceTariff t, TariffRequest r) {
        t.setName(r.name().trim());
        t.setCategory(r.category());
        t.setUnitPrice(Money.scale(r.unitPrice()));
        t.setTaxRate(r.taxRate().setScale(2, RoundingMode.HALF_UP));
        t.setBillingUnit(r.billingUnit());
        t.setRoomType(HospitalSettingsService.blankToNull(r.roomType()));
        t.setDepartmentId(r.departmentId());
        t.setDescription(HospitalSettingsService.blankToNull(r.description()));
        t.setEffectiveFrom(r.effectiveFrom() == null ? (t.getEffectiveFrom() == null ? TimeUtil.today() : t.getEffectiveFrom()) : r.effectiveFrom());
    }

    private static void diff(Map<String, Object> changes, String field, Object old, Object now) {
        if (!Objects.equals(old, now)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("old", old == null ? null : String.valueOf(old));
            m.put("new", now == null ? null : String.valueOf(now));
            changes.put(field, m);
        }
    }

    private TariffView view(ServiceTariff t, Map<UUID, String> deptNames) {
        String dept = t.getDepartmentId() == null ? null : deptNames != null ? deptNames.get(t.getDepartmentId())
                : departments.findById(t.getDepartmentId()).map(Department::getName).orElse(null);
        return new TariffView(t.getId(), t.getCode(), t.getName(), t.getCategory(), t.getUnitPrice(), t.getTaxRate(),
                t.getBillingUnit(), t.getRoomType(), t.getDepartmentId(), dept, t.getDescription(), t.isActive(),
                t.getEffectiveFrom(), t.getUpdatedAt(), t.getVersion());
    }
}
