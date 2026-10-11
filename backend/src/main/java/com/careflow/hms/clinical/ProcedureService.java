package com.careflow.hms.clinical;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.billing.ChargeService;
import com.careflow.hms.billing.Invoice;
import com.careflow.hms.billing.InvoiceLineItem;
import com.careflow.hms.billing.InvoiceRepository;
import com.careflow.hms.billing.ServiceTariff;
import com.careflow.hms.billing.ServiceTariffRepository;
import com.careflow.hms.clinical.ClinicalDtos.CompleteProcedureRequest;
import com.careflow.hms.clinical.ClinicalDtos.ProcedureRequest;
import com.careflow.hms.clinical.ClinicalDtos.ProcedureView;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Procedures: plan, complete (with charge capture when a tariff applies) or cancel. */
@Service
@RequiredArgsConstructor
public class ProcedureService {

    private final ProcedureRecordRepository procedures;
    private final ServiceTariffRepository tariffs;
    private final InvoiceRepository invoices;
    private final UserRepository users;
    private final ChargeService charges;
    private final ClinicalSupport support;
    private final TimelineService timeline;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<ProcedureView> forAdmission(UUID admissionId) {
        support.requireClinical(admissionId);
        return views(procedures.findByAdmissionIdOrderByCreatedAtDesc(admissionId));
    }

    @Transactional
    public ProcedureView plan(UUID admissionId, ProcedureRequest r) {
        Admission a = support.requireOpenClinical(admissionId);
        ServiceTariff t = r.tariffId() == null ? null : requireProcedureTariff(r.tariffId());
        ProcedureRecord p = new ProcedureRecord();
        p.setAdmissionId(a.getId());
        p.setPatientId(a.getPatientId());
        p.setTariffId(t == null ? null : t.getId());
        p.setName(r.name().trim());
        p.setStatus(ProcedureRecord.Status.PLANNED);
        p.setScheduledAt(r.scheduledAt());
        p.setNotes(ClinicalSupport.blankToNull(r.notes()));
        p.setCreatedBy(TenantContext.actorId());
        p.setCreatedAt(Instant.now());
        p = procedures.saveAndFlush(p);
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("PROCEDURE_PLANNED").category(TimelineEvent.Category.CLINICAL)
                .title("Procedure planned: " + p.getName()).status("PLANNED")
                .visibility(TimelineEvent.Visibility.PATIENT).entityType("PROCEDURE").entityId(p.getId()).build());
        audit.record("PROCEDURE_PLANNED", "PROCEDURE", p.getId(), a.getPatientId(), p.getName());
        return views(List.of(p)).get(0);
    }

    @Transactional
    public ProcedureView complete(UUID procedureId, CompleteProcedureRequest r) {
        ProcedureRecord p = support.requireOrg(procedures.findById(procedureId), "Procedure");
        Admission a = support.requireOpenClinical(p.getAdmissionId());
        if (p.getStatus() != ProcedureRecord.Status.PLANNED) {
            throw ApiException.rule("PROCEDURE_NOT_PLANNED", "Only planned procedures can be completed.");
        }
        Instant at = r.performedAt() == null ? Instant.now() : r.performedAt();
        if (at.isAfter(Instant.now().plus(Duration.ofMinutes(5))) || at.isBefore(a.getAdmittedAt().minus(Duration.ofHours(2)))) {
            throw ApiException.badRequest("INVALID_TIME", "The performed time must be during the admission and not in the future.");
        }
        UUID performer = TenantContext.actorId();
        if (r.performedById() != null) {
            performer = users.findByIdAndOrganizationId(r.performedById(), a.getOrganizationId())
                    .filter(u -> u.getRole() == Role.DOCTOR && u.getStatus() == User.Status.ACTIVE)
                    .map(User::getId)
                    .orElseThrow(() -> ApiException.rule("INVALID_PERFORMER", "The performer must be an active doctor."));
        }
        p.setStatus(ProcedureRecord.Status.COMPLETED);
        p.setPerformedAt(at);
        p.setPerformedBy(performer);
        p.setAnaesthesia(ClinicalSupport.blankToNull(r.anaesthesia()));
        p.setFindings(ClinicalSupport.blankToNull(r.findings()));
        p.setComplications(ClinicalSupport.blankToNull(r.complications()));
        if (ClinicalSupport.blankToNull(r.notes()) != null) {
            p.setNotes(r.notes().trim());
        }
        if (p.getTariffId() != null && !p.isChargePosted()) {
            Optional<Invoice> inv = invoices.findActiveByAdmission(a.getId());
            if (inv.isEmpty() || inv.get().getStatus() == Invoice.Status.OPEN) {
                ServiceTariff t = requireProcedureTariff(p.getTariffId());
                charges.postCharge(ChargeService.ChargeRequest.builder()
                        .admissionId(a.getId()).tariffId(t.getId()).category(ServiceTariff.Category.PROCEDURE)
                        .description(p.getName()).serviceDate(TimeUtil.localDate(at))
                        .sourceType(InvoiceLineItem.SourceType.PROCEDURE).sourceId(p.getId()).build());
                p.setChargePosted(true);
            }
        }
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("PROCEDURE_COMPLETED").category(TimelineEvent.Category.CLINICAL)
                .title("Procedure completed: " + p.getName()).status("COMPLETED")
                .visibility(TimelineEvent.Visibility.PATIENT).entityType("PROCEDURE").entityId(p.getId()).occurredAt(at).build());
        if (p.getComplications() != null) {
            timeline.record(TimelineService.Entry.builder()
                    .patientId(a.getPatientId()).admissionId(a.getId())
                    .eventType("PROCEDURE_COMPLICATION").category(TimelineEvent.Category.SAFETY)
                    .title("Complication documented: " + p.getName()).detail(ClinicalSupport.truncate(p.getComplications(), 500))
                    .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("PROCEDURE").entityId(p.getId()).build());
        }
        audit.record("PROCEDURE_COMPLETED", "PROCEDURE", p.getId(), a.getPatientId(), p.getName());
        procedures.flush();
        return views(List.of(p)).get(0);
    }

    @Transactional
    public ProcedureView cancel(UUID procedureId, String reason) {
        ProcedureRecord p = support.requireOrg(procedures.findById(procedureId), "Procedure");
        Admission a = support.requireClinical(p.getAdmissionId());
        if (p.getStatus() != ProcedureRecord.Status.PLANNED) {
            throw ApiException.rule("PROCEDURE_NOT_PLANNED", "Only planned procedures can be cancelled.");
        }
        p.setStatus(ProcedureRecord.Status.CANCELLED);
        p.setNotes((p.getNotes() == null ? "" : p.getNotes() + "\n") + "Cancelled: " + reason.trim());
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("PROCEDURE_CANCELLED").category(TimelineEvent.Category.CLINICAL)
                .title("Procedure cancelled: " + p.getName()).status("CANCELLED")
                .visibility(TimelineEvent.Visibility.PATIENT).entityType("PROCEDURE").entityId(p.getId()).build());
        audit.record("PROCEDURE_CANCELLED", "PROCEDURE", p.getId(), a.getPatientId(), reason);
        procedures.flush();
        return views(List.of(p)).get(0);
    }

    private ServiceTariff requireProcedureTariff(UUID id) {
        ServiceTariff t = tariffs.findByIdAndOrganizationId(id, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Tariff"));
        if (t.getCategory() != ServiceTariff.Category.PROCEDURE || !t.isActive()) {
            throw ApiException.rule("INVALID_TARIFF", "Choose an active procedure tariff.");
        }
        return t;
    }

    List<ProcedureView> views(List<ProcedureRecord> list) {
        Map<UUID, String> names = support.userNames(list.stream().flatMap(p -> Stream.of(p.getPerformedBy(), p.getCreatedBy())).toList());
        Map<UUID, String> tariffNames = new HashMap<>();
        list.stream().map(ProcedureRecord::getTariffId).filter(java.util.Objects::nonNull).distinct()
                .forEach(id -> tariffs.findById(id).ifPresent(t -> tariffNames.put(id, t.getName())));
        return list.stream().map(p -> new ProcedureView(p.getId(), p.getAdmissionId(), p.getTariffId(), tariffNames.get(p.getTariffId()),
                p.getName(), p.getStatus(), p.getScheduledAt(), p.getPerformedAt(), names.get(p.getPerformedBy()), p.getAnaesthesia(),
                p.getFindings(), p.getComplications(), p.getNotes(), names.get(p.getCreatedBy()), p.getCreatedAt(),
                p.isChargePosted(), p.getVersion())).toList();
    }
}
