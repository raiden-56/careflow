package com.careflow.hms.billing;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.admission.BedAssignment;
import com.careflow.hms.admission.BedAssignmentRepository;
import com.careflow.hms.billing.BillingDtos.RoomChargeResult;
import com.careflow.hms.common.Money;
import com.careflow.hms.facility.Bed;
import com.careflow.hms.facility.BedRepository;
import com.careflow.hms.facility.Room;
import com.careflow.hms.facility.RoomRepository;
import com.careflow.hms.facility.Ward;
import com.careflow.hms.facility.WardRepository;
import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bed-day (room) and daily nursing charges, derived from the admission's bed-assignment history.
 *
 * <p>Rules (setting {@code billing.roomChargeMode = CALENDAR_DAY}):
 * <ul>
 *   <li>Each occupied hospital-local calendar day is charged, except the discharge day.</li>
 *   <li>A same-day admission and discharge is charged as one day.</li>
 *   <li>When the patient moved during a day, the room occupied at the end of that day is charged.</li>
 *   <li>For an ongoing stay only completed days (up to yesterday) are posted; finalizing an invoice of a
 *       patient admitted today posts that single day.</li>
 *   <li>Idempotent: a day already carrying a posted ROOM bed-day line is never charged again, and
 *       {@code invoices.room_charges_posted_until} records the last day evaluated.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RoomChargeService {

    public static final String NURSING_TARIFF = "NURSING_DAILY";

    private final AdmissionRepository admissions;
    private final BedAssignmentRepository bedAssignments;
    private final BedRepository beds;
    private final RoomRepository rooms;
    private final WardRepository wards;
    private final ServiceTariffRepository tariffs;
    private final InvoiceLineItemRepository lines;
    private final InvoiceRepository invoices;
    private final ChargeService charges;
    private final BillingSupport support;

    /**
     * Posts outstanding room + nursing charges for a (locked, OPEN) invoice.
     *
     * @param finalizing   true when called while finalizing: an ongoing stay that started today is charged one day
     * @param backdateDays seeding only: timestamp each day's charges just after that day ended
     */
    @Transactional
    public RoomChargeResult post(Invoice invoice, boolean finalizing, boolean backdateDays) {
        List<String> warnings = new ArrayList<>();
        if (invoice.getAdmissionId() == null || invoice.getStatus() != Invoice.Status.OPEN) {
            return new RoomChargeResult(0, 0, Money.ZERO, invoice.getRoomChargesPostedUntil(), warnings);
        }
        Admission a = admissions.findById(invoice.getAdmissionId()).orElse(null);
        if (a == null || a.getStatus() == Admission.Status.CANCELLED) {
            return new RoomChargeResult(0, 0, Money.ZERO, invoice.getRoomChargesPostedUntil(), warnings);
        }
        List<BedAssignment> stays = bedAssignments.findByAdmissionIdOrderByAssignedAtAsc(a.getId()).stream()
                .filter(s -> s.getReleaseReason() != BedAssignment.ReleaseReason.CANCELLED)
                .toList();
        if (stays.isEmpty()) {
            warnings.add("No bed has been assigned yet; there are no room days to charge.");
            return new RoomChargeResult(0, 0, Money.ZERO, invoice.getRoomChargesPostedUntil(), warnings);
        }
        ZoneId zone = support.zone();
        LocalDate today = LocalDate.now(zone);
        LocalDate first = stays.get(0).getAssignedAt().atZone(zone).toLocalDate();
        LocalDate last;
        if (a.getDischargedAt() != null) {
            last = a.getDischargedAt().atZone(zone).toLocalDate().minusDays(1);
            if (last.isBefore(first)) {
                last = first; // same-day admission and discharge = one day
            }
        } else {
            last = today.minusDays(1);
            if (finalizing && last.isBefore(first)) {
                last = first;
            }
        }
        if (last.isAfter(today)) {
            last = today;
        }
        LocalDate start = invoice.getRoomChargesPostedUntil() == null || invoice.getRoomChargesPostedUntil().isBefore(first)
                ? first : invoice.getRoomChargesPostedUntil().plusDays(1);
        if (start.isAfter(last)) {
            return new RoomChargeResult(0, 0, Money.ZERO, invoice.getRoomChargesPostedUntil(), warnings);
        }
        UUID orgId = TenantContext.requireOrganizationId();
        Optional<ServiceTariff> nursing = tariffs.findByOrganizationIdAndCode(orgId, NURSING_TARIFF).filter(ServiceTariff::isActive);
        Map<Room.RoomType, Optional<ServiceTariff>> roomTariffs = new HashMap<>();
        int roomDays = 0;
        int nursingDays = 0;
        BigDecimal amount = Money.ZERO;
        LocalDate postedThrough = invoice.getRoomChargesPostedUntil();
        for (LocalDate d = start; !d.isAfter(last); d = d.plusDays(1)) {
            BedAssignment ba = occupantForDay(stays, d, zone);
            if (ba == null) {
                warnings.add("No bed was occupied on " + d + "; no room charge for that day.");
                postedThrough = d;
                continue;
            }
            Bed bed = beds.findById(ba.getBedId()).orElse(null);
            Room room = bed == null ? null : rooms.findById(bed.getRoomId()).orElse(null);
            if (room == null) {
                warnings.add("Bed record missing for " + d + "; stopped before this day.");
                break;
            }
            Optional<ServiceTariff> tariff = roomTariffs.computeIfAbsent(room.getRoomType(), rt ->
                    tariffs.findFirstByOrganizationIdAndCategoryAndRoomTypeAndActiveTrue(orgId, ServiceTariff.Category.ROOM, rt.name()));
            if (tariff.isEmpty()) {
                warnings.add("No active room tariff for room type " + room.getRoomType() + "; room charges stopped before " + d
                        + ". Configure the tariff and post again.");
                break;
            }
            String ward = wards.findById(bed.getWardId()).map(Ward::getName).orElse("");
            final LocalDate day = d;
            final Bed chargedBed = bed;
            final Room chargedRoom = room;
            final ServiceTariff t = tariff.get();
            Instant stamp = backdateDays ? backdateStamp(day, zone) : null;
            int[] counts = new int[2];
            BigDecimal[] posted = {Money.ZERO};
            Runnable work = () -> {
                if (!lines.existsByInvoiceIdAndSourceTypeAndCategoryAndServiceDateAndStatus(invoice.getId(),
                        InvoiceLineItem.SourceType.BED_DAY, ServiceTariff.Category.ROOM, day, InvoiceLineItem.Status.POSTED)) {
                    InvoiceLineItem line = charges.postCharge(ChargeService.ChargeRequest.builder()
                            .invoiceId(invoice.getId()).tariffId(t.getId()).category(ServiceTariff.Category.ROOM)
                            .description(t.getName() + " - " + ward + ", Room " + chargedRoom.getRoomNumber()
                                    + ", Bed " + chargedBed.getBedNumber())
                            .quantity(BigDecimal.ONE).serviceDate(day)
                            .sourceType(InvoiceLineItem.SourceType.BED_DAY).sourceId(chargedBed.getId()).build());
                    counts[0]++;
                    posted[0] = posted[0].add(line.getTotalAmount());
                }
                if (nursing.isPresent() && !lines.existsByInvoiceIdAndSourceTypeAndCategoryAndServiceDateAndStatus(invoice.getId(),
                        InvoiceLineItem.SourceType.BED_DAY, ServiceTariff.Category.NURSING, day, InvoiceLineItem.Status.POSTED)) {
                    InvoiceLineItem line = charges.postCharge(ChargeService.ChargeRequest.builder()
                            .invoiceId(invoice.getId()).tariffId(nursing.get().getId()).category(ServiceTariff.Category.NURSING)
                            .description(nursing.get().getName()).quantity(BigDecimal.ONE).serviceDate(day)
                            .sourceType(InvoiceLineItem.SourceType.BED_DAY).sourceId(a.getId()).build());
                    counts[1]++;
                    posted[0] = posted[0].add(line.getTotalAmount());
                }
            };
            if (stamp != null) {
                BillingClock.at(stamp, work);
            } else {
                work.run();
            }
            roomDays += counts[0];
            nursingDays += counts[1];
            amount = amount.add(posted[0]);
            postedThrough = d;
        }
        if (postedThrough != null && (invoice.getRoomChargesPostedUntil() == null
                || postedThrough.isAfter(invoice.getRoomChargesPostedUntil()))) {
            invoice.setRoomChargesPostedUntil(postedThrough);
            invoices.save(invoice);
        }
        return new RoomChargeResult(roomDays, nursingDays, Money.scale(amount), postedThrough, warnings);
    }

    /**
     * The bed assignment to charge for a calendar day: the bed occupied at the end of the day; if the
     * patient left during the day (discharge / same-day stay), the last bed occupied that day.
     */
    static BedAssignment occupantForDay(List<BedAssignment> stays, LocalDate day, ZoneId zone) {
        Instant dayStart = day.atStartOfDay(zone).toInstant();
        Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
        BedAssignment atEnd = null;
        BedAssignment lastDuring = null;
        for (BedAssignment s : stays) {
            boolean startedBeforeEnd = s.getAssignedAt().isBefore(dayEnd);
            if (startedBeforeEnd && (s.getReleasedAt() == null || !s.getReleasedAt().isBefore(dayEnd))) {
                atEnd = s;
            }
            if (startedBeforeEnd && (s.getReleasedAt() == null || s.getReleasedAt().isAfter(dayStart))) {
                lastDuring = s;
            }
        }
        return atEnd != null ? atEnd : lastDuring;
    }

    private static Instant backdateStamp(LocalDate day, ZoneId zone) {
        Instant stamp = day.plusDays(1).atTime(LocalTime.of(0, 15)).atZone(zone).toInstant();
        return stamp.isAfter(Instant.now()) ? Instant.now() : stamp;
    }
}
