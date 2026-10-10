package com.careflow.hms.pharmacy;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.billing.InvoiceLineItem;
import com.careflow.hms.billing.InvoiceLineItemRepository;
import com.careflow.hms.common.Money;
import com.careflow.hms.medication.Medication;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.pharmacy.PharmacyDtos.ChargeLineView;
import com.careflow.hms.pharmacy.PharmacyDtos.DispensationView;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Batch-maps dispensations to views (avoids N+1 on history lists). */
@Component
@RequiredArgsConstructor
class DispensationMapper {

    private final PatientRepository patients;
    private final AdmissionRepository admissions;
    private final InventoryBatchRepository batches;
    private final InvoiceLineItemRepository lines;
    private final PharmacySupport support;
    private final EntityManager em;

    List<DispensationView> toViews(List<Dispensation> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, Patient> pts = patients.findByIdIn(list.stream().map(Dispensation::getPatientId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Patient::getId, Function.identity()));
        Map<UUID, Admission> adms = admissions.findByIdIn(list.stream().map(Dispensation::getAdmissionId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Admission::getId, Function.identity()));
        Map<UUID, Medication> meds = support.medicationsById(list.stream().map(Dispensation::getMedicationId).toList());
        Map<UUID, InventoryBatch> bts = new HashMap<>();
        batches.findAllById(list.stream().map(Dispensation::getBatchId).collect(Collectors.toSet()))
                .forEach(b -> bts.put(b.getId(), b));
        Map<UUID, String> names = support.userNames(list.stream().map(Dispensation::getDispensedBy).toList());
        Map<UUID, InvoiceLineItem> charges = new HashMap<>();
        lines.findAllById(list.stream().map(Dispensation::getChargeLineId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .forEach(l -> charges.put(l.getId(), l));
        return list.stream().map(d -> {
            Patient p = pts.get(d.getPatientId());
            Admission a = adms.get(d.getAdmissionId());
            Medication m = meds.get(d.getMedicationId());
            InventoryBatch b = bts.get(d.getBatchId());
            InvoiceLineItem line = d.getChargeLineId() == null ? null : charges.get(d.getChargeLineId());
            BigDecimal charged = line != null && line.getStatus() == InvoiceLineItem.Status.POSTED
                    ? line.getTotalAmount() : Money.ZERO;
            BigDecimal unitPrice = line != null ? line.getUnitPrice() : m == null ? null : Money.scale(m.getUnitPrice());
            return new DispensationView(d.getId(), d.getOrderId(), d.getAdmissionId(),
                    a == null ? null : a.getAdmissionNumber(), d.getPatientId(), p == null ? null : p.fullName(),
                    p == null ? null : p.getMrn(), d.getMedicationId(), m == null ? null : m.getCode(),
                    m == null ? null : m.displayName(), m == null ? null : m.getDispenseUnit(), d.getBatchId(),
                    b == null ? null : b.getBatchNumber(), b == null ? null : b.getExpiryDate(), d.getQuantity(),
                    d.getReturnedQuantity(), d.getQuantity() - d.getReturnedQuantity(), d.getStatus(),
                    names.get(d.getDispensedBy()), d.getDispensedAt(), d.getNotes(), d.getChargeLineId(), unitPrice,
                    Money.scale(charged));
        }).toList();
    }

    /** All posted, reversed and reversal lines that belong to a dispensation, oldest first. */
    List<ChargeLineView> chargeLines(UUID dispensationId) {
        List<InvoiceLineItem> found = em.createQuery("""
                        select l from InvoiceLineItem l
                        where (l.sourceType = :type and l.sourceId = :id)
                           or l.reversesLineId in (select o.id from InvoiceLineItem o where o.sourceType = :type and o.sourceId = :id)
                        order by l.postedAt asc
                        """, InvoiceLineItem.class)
                .setParameter("type", InvoiceLineItem.SourceType.DISPENSATION)
                .setParameter("id", dispensationId)
                .getResultList();
        return found.stream().map(l -> new ChargeLineView(l.getId(), l.getDescription(), l.getServiceDate(), l.getQuantity(),
                l.getUnitPrice(), l.getTotalAmount(), l.getStatus().name(), l.getPostedAt(), l.getReason())).toList();
    }
}
