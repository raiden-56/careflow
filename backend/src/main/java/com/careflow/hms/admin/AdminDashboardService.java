package com.careflow.hms.admin;

import com.careflow.hms.common.Money;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Permissions;
import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hospital operations dashboard. Every number is computed on the server from live tables; reports
 * read other modules' tables (ledger, discharge workflows...) and degrade to zeros when empty.
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    static final double HIGH_OCCUPANCY_PERCENT = 90.0;
    static final Duration CLEANING_ALERT_AFTER = Duration.ofHours(2);
    static final Duration AWAITING_BED_ALERT_AFTER = Duration.ofHours(4);

    private final JdbcTemplate jdbc;

    public record PatientCounts(long totalRegistered, long currentlyAdmitted, long awaitingBed, long dischargeInProgress) {
    }

    public record BedCounts(long total, long available, long occupied, long reserved, long cleaning, long maintenance,
                            double occupancyPercent) {
    }

    public record WardOccupancy(UUID wardId, String code, String name, long total, long occupied, long available,
                                long reserved, long cleaning, long maintenance, double occupancyPercent) {
    }

    public record DayCount(LocalDate date, long admissions, long discharges) {
    }

    public record PendingWork(long pendingTransfers, long pendingBedAssignments, long openPatientRequests,
                              long openIncidents, long dischargeWorkflowsInProgress) {
    }

    public record Financial(BigDecimal todayCollections, BigDecimal outstandingReceivables, long invoicesWithBalance,
                            String collectionsDefinition, String receivablesDefinition) {
    }

    public record DoctorLoad(UUID userId, String name, String department, long openPatients) {
    }

    public record WardNurseLoad(UUID wardId, String wardName, long nursesAssigned, long occupiedBeds,
                                Double patientsPerNurse) {
    }

    public record Workload(List<DoctorLoad> doctors, List<WardNurseLoad> nurseWards) {
    }

    public record OperationalAlert(String severity, String code, String title, String message, String link) {
    }

    public record AdminDashboard(Instant generatedAt, PatientCounts patients, BedCounts beds,
                                 List<WardOccupancy> wards, List<DayCount> trend, PendingWork pending,
                                 Financial financial, Workload workload, List<OperationalAlert> alerts) {
    }

    @Transactional(readOnly = true)
    public AdminDashboard dashboard() {
        CurrentUser u = TenantContext.requireUser();
        UUID org = u.organizationId();
        ZoneId zone = TimeUtil.zone();
        Instant now = Instant.now();

        PatientCounts patients = new PatientCounts(
                count("SELECT count(*) FROM patients WHERE organization_id = ? AND status <> 'ARCHIVED'", org),
                count("SELECT count(*) FROM admissions WHERE organization_id = ? AND status IN ('ADMITTED','DISCHARGE_IN_PROGRESS')", org),
                count("SELECT count(*) FROM admissions WHERE organization_id = ? AND status = 'PENDING_BED'", org),
                count("SELECT count(*) FROM admissions WHERE organization_id = ? AND status = 'DISCHARGE_IN_PROGRESS'", org));

        List<WardOccupancy> wards = wardOccupancy(org);
        long total = wards.stream().mapToLong(WardOccupancy::total).sum();
        long occupied = wards.stream().mapToLong(WardOccupancy::occupied).sum();
        BedCounts beds = new BedCounts(total,
                wards.stream().mapToLong(WardOccupancy::available).sum(), occupied,
                wards.stream().mapToLong(WardOccupancy::reserved).sum(),
                wards.stream().mapToLong(WardOccupancy::cleaning).sum(),
                wards.stream().mapToLong(WardOccupancy::maintenance).sum(),
                percent(occupied, total));

        PendingWork pending = new PendingWork(
                count("SELECT count(*) FROM transfers WHERE organization_id = ? AND status IN ('REQUESTED','APPROVED')", org),
                patients.awaitingBed(),
                count("SELECT count(*) FROM patient_requests WHERE organization_id = ? AND status IN ('OPEN','IN_REVIEW')", org),
                count("SELECT count(*) FROM incident_reports WHERE organization_id = ? AND status <> 'CLOSED'", org),
                count("SELECT count(*) FROM discharge_workflows WHERE organization_id = ? AND status IN ('IN_PROGRESS','READY')", org));

        Financial financial = u.has(Permissions.REPORT_FINANCIAL) ? financial(org, zone) : null;

        return new AdminDashboard(now, patients, beds, wards, trend(org, zone, 14), pending, financial,
                workload(org, wards), alerts(org, wards, now));
    }

    List<WardOccupancy> wardOccupancy(UUID org) {
        return jdbc.query("""
                SELECT w.id, w.code, w.name,
                       count(b.id) AS total,
                       count(b.id) FILTER (WHERE b.status = 'OCCUPIED') AS occupied,
                       count(b.id) FILTER (WHERE b.status = 'AVAILABLE') AS available,
                       count(b.id) FILTER (WHERE b.status = 'RESERVED') AS reserved,
                       count(b.id) FILTER (WHERE b.status = 'CLEANING') AS cleaning,
                       count(b.id) FILTER (WHERE b.status = 'MAINTENANCE') AS maintenance
                FROM wards w
                LEFT JOIN beds b ON b.ward_id = w.id AND b.active
                WHERE w.organization_id = ? AND w.active
                GROUP BY w.id, w.code, w.name
                ORDER BY w.name
                """, (rs, i) -> {
            long t = rs.getLong("total");
            long o = rs.getLong("occupied");
            return new WardOccupancy(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), t, o,
                    rs.getLong("available"), rs.getLong("reserved"), rs.getLong("cleaning"), rs.getLong("maintenance"),
                    percent(o, t));
        }, org);
    }

    List<DayCount> trend(UUID org, ZoneId zone, int days) {
        LocalDate today = LocalDate.now(zone);
        LocalDate first = today.minusDays(days - 1L);
        Timestamp from = Timestamp.from(first.atStartOfDay(zone).toInstant());
        Map<LocalDate, long[]> byDay = new HashMap<>();
        jdbc.query("""
                SELECT (admitted_at AT TIME ZONE ?)::date AS d, count(*) AS n FROM admissions
                WHERE organization_id = ? AND admitted_at >= ? AND status <> 'CANCELLED' GROUP BY d
                """, rs -> {
            byDay.computeIfAbsent(rs.getDate("d").toLocalDate(), k -> new long[2])[0] = rs.getLong("n");
        }, zone.getId(), org, from);
        jdbc.query("""
                SELECT (discharged_at AT TIME ZONE ?)::date AS d, count(*) AS n FROM admissions
                WHERE organization_id = ? AND discharged_at >= ? AND status = 'DISCHARGED' GROUP BY d
                """, rs -> {
            byDay.computeIfAbsent(rs.getDate("d").toLocalDate(), k -> new long[2])[1] = rs.getLong("n");
        }, zone.getId(), org, from);
        List<DayCount> result = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            long[] v = byDay.getOrDefault(d, new long[2]);
            result.add(new DayCount(d, v[0], v[1]));
        }
        return result;
    }

    private Financial financial(UUID org, ZoneId zone) {
        Instant start = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        BigDecimal paymentsNet = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM ledger_entries
                WHERE organization_id = ? AND entry_type IN ('PAYMENT','PAYMENT_REVERSAL') AND created_at >= ?
                """, BigDecimal.class, org, Timestamp.from(start));
        Map<String, Object> recv = jdbc.queryForMap("""
                SELECT COALESCE(SUM(balance), 0) AS total, count(*) AS invoices FROM (
                    SELECT SUM(l.amount) AS balance FROM ledger_entries l
                    JOIN invoices i ON i.id = l.invoice_id
                    WHERE l.organization_id = ? AND i.status <> 'CANCELLED'
                    GROUP BY l.invoice_id) x
                WHERE balance > 0
                """, org);
        // Payments are negative ledger amounts; collections are reported as a positive figure.
        BigDecimal collections = Money.scale(paymentsNet == null ? BigDecimal.ZERO : paymentsNet.negate());
        return new Financial(collections, Money.scale((BigDecimal) recv.get("total")), ((Number) recv.get("invoices")).longValue(),
                "Collections: payments received today (hospital time) minus payment reversals posted today. Refunds and adjustments are excluded.",
                "Receivables: sum of positive ledger balances across non-cancelled invoices (amount still owed by patients/payers). Credit balances are excluded.");
    }

    private Workload workload(UUID org, List<WardOccupancy> wards) {
        List<DoctorLoad> doctors = jdbc.query("""
                SELECT u.id, u.full_name, d.name AS dept,
                       count(a.id) FILTER (WHERE a.status IN ('PENDING_BED','ADMITTED','DISCHARGE_IN_PROGRESS')) AS open_patients
                FROM users u
                LEFT JOIN staff_profiles sp ON sp.user_id = u.id
                LEFT JOIN departments d ON d.id = sp.department_id
                LEFT JOIN admissions a ON a.attending_doctor_id = u.id
                WHERE u.organization_id = ? AND u.role_code = 'DOCTOR' AND u.status = 'ACTIVE'
                GROUP BY u.id, u.full_name, d.name
                ORDER BY open_patients DESC, u.full_name
                """, (rs, i) -> new DoctorLoad(rs.getObject("id", UUID.class), rs.getString("full_name"),
                rs.getString("dept"), rs.getLong("open_patients")), org);
        Map<UUID, Long> nurses = new HashMap<>();
        jdbc.query("""
                SELECT a.ward_id, count(DISTINCT a.user_id) AS n FROM staff_ward_assignments a
                JOIN users u ON u.id = a.user_id
                WHERE a.organization_id = ? AND a.active AND u.role_code = 'NURSE' AND u.status = 'ACTIVE'
                GROUP BY a.ward_id
                """, rs -> {
            nurses.put(rs.getObject("ward_id", UUID.class), rs.getLong("n"));
        }, org);
        List<WardNurseLoad> nurseWards = wards.stream().map(w -> {
            long n = nurses.getOrDefault(w.wardId(), 0L);
            Double ratio = n == 0 ? null : BigDecimal.valueOf(w.occupied()).divide(BigDecimal.valueOf(n), 1, RoundingMode.HALF_UP).doubleValue();
            return new WardNurseLoad(w.wardId(), w.name(), n, w.occupied(), ratio);
        }).toList();
        return new Workload(doctors, nurseWards);
    }

    private List<OperationalAlert> alerts(UUID org, List<WardOccupancy> wards, Instant now) {
        List<OperationalAlert> alerts = new ArrayList<>();
        for (WardOccupancy w : wards) {
            if (w.total() > 0 && w.occupancyPercent() >= HIGH_OCCUPANCY_PERCENT) {
                alerts.add(new OperationalAlert(w.occupancyPercent() >= 100 ? "CRITICAL" : "WARNING", "WARD_HIGH_OCCUPANCY",
                        w.name() + " is " + Math.round(w.occupancyPercent()) + "% occupied",
                        w.occupied() + " of " + w.total() + " beds occupied; " + w.available() + " available.",
                        "/admin/beds"));
            }
        }
        jdbc.query("""
                SELECT w.name AS ward, r.room_number, b.bed_number, b.status_changed_at FROM beds b
                JOIN rooms r ON r.id = b.room_id JOIN wards w ON w.id = b.ward_id
                WHERE b.organization_id = ? AND b.active AND b.status = 'CLEANING' AND b.status_changed_at < ?
                ORDER BY b.status_changed_at
                """, rs -> {
            Instant since = rs.getTimestamp("status_changed_at").toInstant();
            long hours = Duration.between(since, now).toHours();
            alerts.add(new OperationalAlert("WARNING", "BED_CLEANING_DELAYED",
                    "Bed " + rs.getString("room_number") + "-" + rs.getString("bed_number") + " in cleaning for " + hours + " h",
                    rs.getString("ward") + ": cleaning has exceeded the 2-hour turnaround target.", "/admin/beds"));
        }, org, Timestamp.from(now.minus(CLEANING_ALERT_AFTER)));
        long longWaits = count2("SELECT count(*) FROM admissions WHERE organization_id = ? AND status = 'PENDING_BED' AND admitted_at < ?",
                org, Timestamp.from(now.minus(AWAITING_BED_ALERT_AFTER)));
        if (longWaits > 0) {
            alerts.add(new OperationalAlert("WARNING", "AWAITING_BED_LONG",
                    longWaits + (longWaits == 1 ? " patient has" : " patients have") + " waited more than 4 h for a bed",
                    "Review bed availability and pending transfers.", "/admin/admissions"));
        }
        long severe = count2("SELECT count(*) FROM incident_reports WHERE organization_id = ? AND status = ? AND severity IN ('SEVERE','DEATH')",
                org, "REPORTED");
        if (severe > 0) {
            alerts.add(new OperationalAlert("CRITICAL", "SEVERE_INCIDENT_UNREVIEWED",
                    severe + " severe safety incident" + (severe == 1 ? " is" : "s are") + " awaiting review",
                    "Assign a reviewer and start the review.", "/admin/incidents"));
        }
        long urgentTransfers = count2("SELECT count(*) FROM transfers WHERE organization_id = ? AND status = ? AND priority = 'URGENT'",
                org, "REQUESTED");
        if (urgentTransfers > 0) {
            alerts.add(new OperationalAlert("WARNING", "URGENT_TRANSFER_PENDING",
                    urgentTransfers + " urgent transfer request" + (urgentTransfers == 1 ? "" : "s") + " pending",
                    "Approve a destination bed or reject with a reason.", "/admin/transfers"));
        }
        return alerts;
    }

    static double percent(long part, long whole) {
        if (whole <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(part * 100.0 / whole).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private long count(String sql, UUID org) {
        Long n = jdbc.queryForObject(sql, Long.class, org);
        return n == null ? 0 : n;
    }

    private long count2(String sql, UUID org, Object arg) {
        Long n = jdbc.queryForObject(sql, Long.class, org, arg);
        return n == null ? 0 : n;
    }
}
