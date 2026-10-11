package com.careflow.hms.billing;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.admission.BedAssignment;
import com.careflow.hms.admission.BedAssignmentRepository;
import com.careflow.hms.billing.BillingDtos.AdmissionRef;
import com.careflow.hms.billing.BillingDtos.PatientRef;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.facility.Bed;
import com.careflow.hms.facility.BedRepository;
import com.careflow.hms.facility.DepartmentRepository;
import com.careflow.hms.facility.Room;
import com.careflow.hms.facility.RoomRepository;
import com.careflow.hms.facility.Ward;
import com.careflow.hms.facility.WardRepository;
import com.careflow.hms.org.Organization;
import com.careflow.hms.org.OrganizationRepository;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.security.AccessPolicy;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Shared lookups and guards for the billing services (tenant + object-level checks, names, refs). */
@Component
@RequiredArgsConstructor
public class BillingSupport {

    private final InvoiceRepository invoices;
    private final AccessPolicy access;
    private final PatientRepository patients;
    private final AdmissionRepository admissions;
    private final DepartmentRepository departments;
    private final UserRepository users;
    private final BedAssignmentRepository bedAssignments;
    private final BedRepository beds;
    private final RoomRepository rooms;
    private final WardRepository wards;
    private final OrganizationRepository organizations;

    /** Loads an invoice of the caller's organization and enforces FINANCIAL access to its patient. */
    public Invoice requireInvoice(UUID invoiceId) {
        UUID orgId = TenantContext.requireOrganizationId();
        Invoice inv = invoices.findByIdAndOrganizationId(invoiceId, orgId)
                .orElseThrow(() -> ApiException.notFound("Invoice"));
        requireFinancial(inv);
        return inv;
    }

    /** Row-locks the invoice (payments, adjustments, finalization, room charges) after the access check. */
    public Invoice lockInvoice(UUID invoiceId) {
        UUID orgId = TenantContext.requireOrganizationId();
        Invoice inv = invoices.lockById(invoiceId, orgId).orElseThrow(() -> ApiException.notFound("Invoice"));
        requireFinancial(inv);
        return inv;
    }

    public void requireFinancial(Invoice inv) {
        if (TenantContext.currentUser().isPresent()) {
            try {
                access.requirePatient(inv.getPatientId(), AccessPolicy.Scope.FINANCIAL);
            } catch (ApiException e) {
                // Never reveal another patient's invoice to a portal user; staff get a clear 403.
                if (e.getStatus().value() == 404) {
                    throw ApiException.notFound("Invoice");
                }
                throw e;
            }
        }
    }

    public static void requireNotCancelled(Invoice inv) {
        if (inv.getStatus() == Invoice.Status.CANCELLED) {
            throw ApiException.rule("INVOICE_CANCELLED", "Invoice " + inv.getInvoiceNumber() + " is cancelled.");
        }
    }

    public static void requireOpen(Invoice inv) {
        if (inv.getStatus() != Invoice.Status.OPEN) {
            throw ApiException.rule("INVOICE_NOT_OPEN", "Invoice " + inv.getInvoiceNumber() + " is "
                    + inv.getStatus().name().toLowerCase() + ". Reopen it to change charges or estimates.");
        }
    }

    /** Organization timezone (works for background jobs where there is no signed-in user). */
    public ZoneId zone() {
        return TenantContext.currentUser().map(CurrentUser::zone).orElseGet(() ->
                organizations.findById(TenantContext.requireOrganizationId()).map(Organization::zone)
                        .orElse(TimeUtil.DEFAULT_ZONE));
    }

    public LocalDate today() {
        return LocalDate.now(zone());
    }

    /** Batch-resolves user display names. */
    public Map<UUID, String> names(Collection<UUID> ids) {
        Map<UUID, String> result = new HashMap<>();
        users.findAllById(ids.stream().filter(Objects::nonNull).distinct().toList())
                .forEach(u -> result.put(u.getId(), u.getFullName()));
        return result;
    }

    public String name(UUID userId) {
        return userId == null ? null : users.findById(userId).map(User::getFullName).orElse(null);
    }

    public Patient patient(UUID patientId) {
        return patients.findById(patientId).orElseThrow(() -> ApiException.notFound("Patient"));
    }

    public PatientRef patientRef(Patient p) {
        return new PatientRef(p.getId(), p.getMrn(), p.fullName(), p.ageYears(today()), p.getGender().name(), p.getPhone());
    }

    public AdmissionRef admissionRef(UUID admissionId) {
        if (admissionId == null) {
            return null;
        }
        Admission a = admissions.findById(admissionId).orElse(null);
        if (a == null) {
            return null;
        }
        String dept = departments.findById(a.getDepartmentId()).map(d -> d.getName()).orElse(null);
        String doctor = name(a.getAttendingDoctorId());
        String location = bedAssignments.findByAdmissionIdAndReleasedAtIsNull(a.getId())
                .map(this::describeBed)
                .orElseGet(() -> bedAssignments.findByAdmissionIdOrderByAssignedAtAsc(a.getId()).stream()
                        .reduce((x, y) -> y).map(this::describeBed).orElse(null));
        return new AdmissionRef(a.getId(), a.getAdmissionNumber(), a.getStatus(), a.getAdmittedAt(),
                a.getDischargedAt(), a.getDepartmentId(), dept, doctor, location);
    }

    public String describeBed(BedAssignment ba) {
        return describeBed(ba.getBedId());
    }

    public String describeBed(UUID bedId) {
        Bed b = beds.findById(bedId).orElse(null);
        if (b == null) {
            return null;
        }
        String ward = wards.findById(b.getWardId()).map(Ward::getName).orElse("?");
        String room = rooms.findById(b.getRoomId()).map(Room::getRoomNumber).orElse("?");
        return ward + ", Room " + room + ", Bed " + b.getBedNumber();
    }

    // ------------------------------------------------------------------ formatting (PDF / messages)

    /** Indian digit grouping, e.g. 123456.5 -> "1,23,456.50". */
    public static String inr(BigDecimal v) {
        BigDecimal x = (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
        boolean neg = x.signum() < 0;
        x = x.abs();
        String[] parts = x.toPlainString().split("\\.");
        String whole = parts[0];
        StringBuilder sb = new StringBuilder();
        int len = whole.length();
        if (len <= 3) {
            sb.append(whole);
        } else {
            String last3 = whole.substring(len - 3);
            String rest = whole.substring(0, len - 3);
            StringBuilder r = new StringBuilder();
            int i = rest.length();
            while (i > 2) {
                r.insert(0, "," + rest.substring(i - 2, i));
                i -= 2;
            }
            r.insert(0, rest.substring(0, i));
            sb.append(r).append(',').append(last3);
        }
        return (neg ? "-" : "") + sb + "." + parts[1];
    }

    /** "INR 1,23,456.50" for messages. */
    public static String rupees(BigDecimal v) {
        return "INR " + inr(v);
    }

    /** Payment method display label (UPI, Net banking, Demand draft...). */
    public static String method(Payment.Method m) {
        if (m == null) {
            return "";
        }
        return switch (m) {
            case CASH -> "Cash";
            case CARD -> "Card";
            case UPI -> "UPI";
            case NET_BANKING -> "Net banking";
            case CHEQUE -> "Cheque";
            case DEMAND_DRAFT -> "Demand draft";
            case ONLINE -> "Online";
        };
    }

    public static String label(Enum<?> e) {
        if (e == null) {
            return "";
        }
        String s = e.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
