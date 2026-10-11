package com.careflow.hms.admin;

import com.careflow.hms.admin.ReportDtos.ClinicalQualityReport;
import com.careflow.hms.admin.ReportDtos.CompletenessRow;
import com.careflow.hms.admin.ReportDtos.DailyRow;
import com.careflow.hms.admin.ReportDtos.Definition;
import com.careflow.hms.admin.ReportDtos.DelayRow;
import com.careflow.hms.admin.ReportDtos.DischargeDelays;
import com.careflow.hms.admin.ReportDtos.FollowUpSummary;
import com.careflow.hms.admin.ReportDtos.InvestigationSummary;
import com.careflow.hms.admin.ReportDtos.LosRow;
import com.careflow.hms.admin.ReportDtos.NamedCount;
import com.careflow.hms.admin.ReportDtos.OperationalReport;
import com.careflow.hms.admin.ReportDtos.ReportFilter;
import com.careflow.hms.admin.ReportDtos.SafetyAlertSummary;
import com.careflow.hms.admin.ReportDtos.Summary;
import com.careflow.hms.admin.ReportDtos.TaskSummary;
import com.careflow.hms.admin.ReportDtos.TransferRow;
import com.careflow.hms.admin.ReportDtos.WardRow;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.facility.DepartmentRepository;
import com.careflow.hms.facility.WardRepository;
import com.careflow.hms.org.SettingsService;
import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operational analytics over ADT, bed, discharge, nursing task, medication and follow-up tables.
 * All queries are organization-scoped and tolerate empty tables (zeros / nulls, never division by zero).
 */
@Service
@RequiredArgsConstructor
public class OperationalReportService {

    static final int MAX_RANGE_DAYS = 366;

    private final JdbcTemplate jdbc;
    private final DepartmentRepository departments;
    private final WardRepository wards;
    private final SettingsService settings;

    /** Resolved filter with SQL fragments for admission- and bed-scoped queries. */
    record Scope(UUID org, ZoneId zone, LocalDate fromDate, LocalDate toDate, UUID departmentId, UUID wardId) {

        Timestamp from() {
            return Timestamp.from(fromDate.atStartOfDay(zone).toInstant());
        }

        /** Exclusive end: the start of the day after {@code toDate}, capped at now. */
        Timestamp to() {
            Instant end = toDate.plusDays(1).atStartOfDay(zone).toInstant();
            Instant now = Instant.now();
            return Timestamp.from(end.isAfter(now) ? now : end);
        }

        /** Predicate on an admissions alias. */
        String admission(String a, List<Object> args) {
            StringBuilder sb = new StringBuilder();
            if (departmentId != null) {
                sb.append(" AND ").append(a).append(".department_id = ?");
                args.add(departmentId);
            }
            if (wardId != null) {
                sb.append(" AND EXISTS (SELECT 1 FROM bed_assignments sba JOIN beds sb ON sb.id = sba.bed_id WHERE sba.admission_id = ")
                        .append(a).append(".id AND sb.ward_id = ?)");
                args.add(wardId);
            }
            return sb.toString();
        }

        /** Predicate on a beds alias (department filter = wards belonging to the department). */
        String bed(String b, List<Object> args) {
            StringBuilder sb = new StringBuilder();
            if (wardId != null) {
                sb.append(" AND ").append(b).append(".ward_id = ?");
                args.add(wardId);
            }
            if (departmentId != null) {
                sb.append(" AND ").append(b).append(".ward_id IN (SELECT id FROM wards WHERE department_id = ?)");
                args.add(departmentId);
            }
            return sb.toString();
        }
    }

    public Scope scope(LocalDate from, LocalDate to, UUID departmentId, UUID wardId) {
        UUID org = TenantContext.requireOrganizationId();
        ZoneId zone = TimeUtil.zone();
        LocalDate today = LocalDate.now(zone);
        LocalDate t = to == null ? today : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        if (f.isAfter(t)) {
            throw ApiException.badRequest("INVALID_RANGE", "The start date must be on or before the end date.");
        }
        if (ChronoUnit.DAYS.between(f, t) >= MAX_RANGE_DAYS) {
            throw ApiException.badRequest("RANGE_TOO_LONG", "Reports cover at most " + MAX_RANGE_DAYS + " days.");
        }
        if (f.isAfter(today)) {
            throw ApiException.badRequest("INVALID_RANGE", "The report period cannot start in the future.");
        }
        if (departmentId != null) {
            departments.findByIdAndOrganizationId(departmentId, org).orElseThrow(() -> ApiException.notFound("Department"));
        }
        if (wardId != null) {
            wards.findByIdAndOrganizationId(wardId, org).orElseThrow(() -> ApiException.notFound("Ward"));
        }
        return new Scope(org, zone, f, t, departmentId, wardId);
    }

    private ReportFilter filter(Scope s) {
        String dept = s.departmentId() == null ? null : departments.findById(s.departmentId()).map(d -> d.getName()).orElse(null);
        String ward = s.wardId() == null ? null : wards.findById(s.wardId()).map(w -> w.getName()).orElse(null);
        return new ReportFilter(s.fromDate(), s.toDate(), s.departmentId(), dept, s.wardId(), ward, s.zone().getId());
    }

    // ================================================================== operational

    @Transactional(readOnly = true)
    public OperationalReport operational(LocalDate from, LocalDate to, UUID departmentId, UUID wardId) {
        Scope s = scope(from, to, departmentId, wardId);
        long bedsInScope = bedsInScope(s);
        List<DailyRow> daily = daily(s, bedsInScope);
        List<WardRow> wardRows = wardRows(s);
        List<LosRow> los = lengthOfStay(s);
        DischargeDelays delays = dischargeDelays(s);
        TaskSummary tasks = tasks(s);
        List<TransferRow> transfers = transfers(s);
        FollowUpSummary followUps = followUps(s);

        long admissions = daily.stream().mapToLong(DailyRow::admissions).sum();
        long discharges = daily.stream().mapToLong(DailyRow::discharges).sum();
        double avgOccupancy = daily.isEmpty() ? 0.0 : round1(daily.stream().mapToDouble(DailyRow::occupancyPercent).average().orElse(0));
        long totalDischarges = los.stream().mapToLong(LosRow::discharges).sum();
        Double avgLos = totalDischarges == 0 ? null : round1(los.stream()
                .filter(r -> r.averageDays() != null)
                .mapToDouble(r -> r.averageDays() * r.discharges()).sum() / totalDischarges);
        long releases = wardRows.stream().mapToLong(WardRow::bedReleases).sum();
        Double avgCleaning = averageCleaningMinutes(s, null);
        long transfersCompleted = transfers.stream().filter(t -> "COMPLETED".equals(t.status())).count();

        Summary summary = new Summary(admissions, discharges, avgLos, avgOccupancy, bedsInScope, avgCleaning,
                bedsInScope == 0 ? 0.0 : round2((double) releases / bedsInScope), transfers.size(), transfersCompleted,
                followUps.completionPercent(), tasks.overdueMedicationDoses(),
                tasks.medicationExceptions().stream().mapToLong(NamedCount::count).sum(),
                tasks.openCareTasks(), tasks.overdueCareTasks());
        return new OperationalReport(Instant.now(), filter(s), summary, daily, wardRows, los, delays, tasks, transfers,
                followUps, operationalDefinitions(tasks.overdueGraceMinutes()));
    }

    private long bedsInScope(Scope s) {
        List<Object> args = new ArrayList<>(List.of(s.org()));
        String where = s.bed("b", args);
        Long n = jdbc.queryForObject("SELECT count(*) FROM beds b JOIN wards w ON w.id = b.ward_id "
                + "WHERE b.organization_id = ? AND b.active AND w.active" + where, Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    private List<DailyRow> daily(Scope s, long beds) {
        Map<LocalDate, long[]> rows = new HashMap<>();
        List<Object> a1 = new ArrayList<>(List.of(s.zone().getId(), s.org(), s.from(), s.to()));
        String w1 = s.admission("a", a1);
        jdbc.query("SELECT (a.admitted_at AT TIME ZONE ?)::date AS d, count(*) AS n FROM admissions a "
                + "WHERE a.organization_id = ? AND a.admitted_at >= ? AND a.admitted_at < ? AND a.status <> 'CANCELLED'"
                + w1 + " GROUP BY d", rs -> {
            rows.computeIfAbsent(rs.getDate("d").toLocalDate(), k -> new long[3])[0] = rs.getLong("n");
        }, a1.toArray());
        List<Object> a2 = new ArrayList<>(List.of(s.zone().getId(), s.org(), s.from(), s.to()));
        String w2 = s.admission("a", a2);
        jdbc.query("SELECT (a.discharged_at AT TIME ZONE ?)::date AS d, count(*) AS n FROM admissions a "
                + "WHERE a.organization_id = ? AND a.discharged_at >= ? AND a.discharged_at < ? AND a.status = 'DISCHARGED'"
                + w2 + " GROUP BY d", rs -> {
            rows.computeIfAbsent(rs.getDate("d").toLocalDate(), k -> new long[3])[1] = rs.getLong("n");
        }, a2.toArray());
        // Midnight census: beds holding an open assignment at the end of each day (or now, for today).
        List<Object> a3 = new ArrayList<>();
        a3.add(s.org());
        String w3 = s.bed("b", a3);
        a3.add(java.sql.Date.valueOf(s.fromDate()));
        a3.add(java.sql.Date.valueOf(s.toDate()));
        // The zone id comes from a validated java.time.ZoneId (letters, digits, '/', '_', '+', '-').
        String zoneLiteral = "'" + s.zone().getId().replaceAll("[^A-Za-z0-9/_+\\-]", "") + "'";
        jdbc.query("SELECT gs.day::date AS d, (SELECT count(*) FROM bed_assignments ba JOIN beds b ON b.id = ba.bed_id"
                + " WHERE ba.organization_id = ? AND ba.assigned_at <= t.at AND (ba.released_at IS NULL OR ba.released_at > t.at)"
                + w3 + ") AS n"
                + " FROM generate_series(?::date, ?::date, interval '1 day') AS gs(day)"
                + " CROSS JOIN LATERAL (SELECT LEAST((gs.day + interval '1 day') AT TIME ZONE " + zoneLiteral + ", now()) AS at) t",
                rs -> {
                    rows.computeIfAbsent(rs.getDate("d").toLocalDate(), k -> new long[3])[2] = rs.getLong("n");
                }, a3.toArray());
        List<DailyRow> result = new ArrayList<>();
        for (LocalDate d = s.fromDate(); !d.isAfter(s.toDate()); d = d.plusDays(1)) {
            long[] v = rows.getOrDefault(d, new long[3]);
            result.add(new DailyRow(d, v[0], v[1], v[2], AdminDashboardService.percent(v[2], beds)));
        }
        return result;
    }

    private List<WardRow> wardRows(Scope s) {
        List<Object> args = new ArrayList<>();
        args.add(s.from());
        args.add(s.to());
        args.add(s.from());
        args.add(s.to());
        args.add(s.org());
        StringBuilder where = new StringBuilder();
        if (s.wardId() != null) {
            where.append(" AND w.id = ?");
            args.add(s.wardId());
        }
        if (s.departmentId() != null) {
            where.append(" AND w.department_id = ?");
            args.add(s.departmentId());
        }
        Map<UUID, Double> cleaning = cleaningByWard(s);
        return jdbc.query("""
                SELECT w.id, w.name,
                       (SELECT count(*) FROM beds b WHERE b.ward_id = w.id AND b.active) AS beds,
                       (SELECT count(*) FROM beds b WHERE b.ward_id = w.id AND b.active AND b.status = 'OCCUPIED') AS occupied,
                       (SELECT count(*) FROM bed_assignments ba JOIN beds b ON b.id = ba.bed_id
                         WHERE b.ward_id = w.id AND ba.assigned_at >= ? AND ba.assigned_at < ?) AS assigned,
                       (SELECT count(*) FROM bed_assignments ba JOIN beds b ON b.id = ba.bed_id
                         WHERE b.ward_id = w.id AND ba.released_at >= ? AND ba.released_at < ?) AS released
                FROM wards w WHERE w.organization_id = ? AND w.active""" + where + " ORDER BY w.name",
                (rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    long beds = rs.getLong("beds");
                    long occ = rs.getLong("occupied");
                    long rel = rs.getLong("released");
                    return new WardRow(id, rs.getString("name"), beds, occ, AdminDashboardService.percent(occ, beds),
                            rs.getLong("assigned"), rel, cleaning.get(id), beds == 0 ? 0.0 : round2((double) rel / beds));
                }, args.toArray());
    }

    private static final String CLEANING_SQL = """
            SELECT %s AS k, avg(extract(epoch FROM (h.next_at - h.changed_at)) / 60.0) AS mins FROM (
                SELECT hh.bed_id, b.ward_id, hh.to_status, hh.changed_at,
                       lead(hh.changed_at) OVER (PARTITION BY hh.bed_id ORDER BY hh.changed_at) AS next_at
                FROM bed_status_history hh JOIN beds b ON b.id = hh.bed_id
                WHERE hh.organization_id = ? %s) h
            WHERE h.to_status = 'CLEANING' AND h.next_at IS NOT NULL AND h.changed_at >= ? AND h.changed_at < ?
            %s
            """;

    private Map<UUID, Double> cleaningByWard(Scope s) {
        List<Object> args = new ArrayList<>(List.of(s.org()));
        String where = s.bed("b", args);
        args.add(s.from());
        args.add(s.to());
        Map<UUID, Double> result = new HashMap<>();
        jdbc.query(CLEANING_SQL.formatted("h.ward_id", where, "GROUP BY h.ward_id"), rs -> {
            BigDecimal m = rs.getBigDecimal("mins");
            if (m != null) {
                result.put(rs.getObject("k", UUID.class), round1(m.doubleValue()));
            }
        }, args.toArray());
        return result;
    }

    private Double averageCleaningMinutes(Scope s, UUID ward) {
        List<Object> args = new ArrayList<>(List.of(s.org()));
        String where = s.bed("b", args);
        args.add(s.from());
        args.add(s.to());
        BigDecimal m = jdbc.query(CLEANING_SQL.formatted("1", where, ""), rs -> rs.next() ? rs.getBigDecimal("mins") : null,
                args.toArray());
        return m == null ? null : round1(m.doubleValue());
    }

    private List<LosRow> lengthOfStay(Scope s) {
        List<Object> args = new ArrayList<>(List.of(s.org(), s.from(), s.to()));
        String where = s.admission("a", args);
        return jdbc.query("""
                SELECT COALESCE(d.name, 'Unassigned') AS dept, count(*) AS n,
                       avg(extract(epoch FROM (a.discharged_at - a.admitted_at)) / 86400.0) AS avg_days,
                       max(extract(epoch FROM (a.discharged_at - a.admitted_at)) / 86400.0) AS max_days
                FROM admissions a LEFT JOIN departments d ON d.id = a.department_id
                WHERE a.organization_id = ? AND a.status = 'DISCHARGED' AND a.discharged_at >= ? AND a.discharged_at < ?
                """ + where + " GROUP BY d.name ORDER BY d.name", (rs, i) -> new LosRow(rs.getString("dept"), rs.getLong("n"),
                nullableRound1(rs.getBigDecimal("avg_days")), nullableRound1(rs.getBigDecimal("max_days"))), args.toArray());
    }

    private DischargeDelays dischargeDelays(Scope s) {
        List<Object> args = new ArrayList<>(List.of(s.org(), s.from(), s.to()));
        String where = s.admission("a", args);
        Map<String, Object> overall = jdbc.queryForMap("""
                SELECT count(*) AS started,
                       count(*) FILTER (WHERE w.status = 'COMPLETED') AS completed,
                       avg(extract(epoch FROM (w.actual_departure_at - w.initiated_at)) / 3600.0)
                           FILTER (WHERE w.actual_departure_at IS NOT NULL) AS hours_to_departure,
                       avg(extract(epoch FROM (w.actual_departure_at - w.planned_discharge_at)) / 3600.0)
                           FILTER (WHERE w.actual_departure_at IS NOT NULL AND w.planned_discharge_at IS NOT NULL) AS beyond_planned
                FROM discharge_workflows w JOIN admissions a ON a.id = w.admission_id
                WHERE w.organization_id = ? AND w.initiated_at >= ? AND w.initiated_at < ?""" + where, args.toArray());
        List<Object> args2 = new ArrayList<>(List.of(s.org(), s.from(), s.to()));
        String where2 = s.admission("a", args2);
        List<DelayRow> steps = jdbc.query("""
                SELECT st.template_code, min(st.name) AS name, min(st.category) AS category, min(st.step_order) AS ord,
                       count(*) AS total,
                       count(*) FILTER (WHERE st.status = 'COMPLETED') AS completed,
                       count(*) FILTER (WHERE st.status IN ('NOT_STARTED','IN_PROGRESS','BLOCKED') AND w.status IN ('IN_PROGRESS','READY')) AS open,
                       count(*) FILTER (WHERE st.status = 'BLOCKED') AS blocked,
                       avg(extract(epoch FROM (st.completed_at - w.initiated_at)) / 3600.0) FILTER (WHERE st.completed_at IS NOT NULL) AS hours
                FROM discharge_steps st
                JOIN discharge_workflows w ON w.id = st.workflow_id
                JOIN admissions a ON a.id = w.admission_id
                WHERE w.organization_id = ? AND w.initiated_at >= ? AND w.initiated_at < ?""" + where2 + " GROUP BY st.template_code ORDER BY ord",
                (rs, i) -> new DelayRow(rs.getString("template_code"),
                rs.getString("name"), rs.getString("category"), rs.getLong("total"), rs.getLong("completed"),
                rs.getLong("open"), rs.getLong("blocked"), nullableRound1(rs.getBigDecimal("hours"))), args2.toArray());
        return new DischargeDelays(((Number) overall.get("started")).longValue(), ((Number) overall.get("completed")).longValue(),
                nullableRound1((BigDecimal) overall.get("hours_to_departure")), nullableRound1((BigDecimal) overall.get("beyond_planned")),
                steps);
    }

    private TaskSummary tasks(Scope s) {
        Timestamp now = Timestamp.from(Instant.now());
        List<Object> a1 = new ArrayList<>(List.of(now, s.org()));
        String w1 = s.admission("a", a1);
        Map<String, Object> open = jdbc.queryForMap("""
                SELECT count(*) AS open, count(*) FILTER (WHERE t.due_at < ?) AS overdue
                FROM care_tasks t JOIN admissions a ON a.id = t.admission_id
                WHERE t.organization_id = ? AND t.status IN ('OPEN','IN_PROGRESS')""" + w1, a1.toArray());
        List<Object> a2 = new ArrayList<>(List.of(s.org()));
        String w2 = s.admission("a", a2);
        List<NamedCount> byType = jdbc.query("""
                SELECT t.task_type AS name, count(*) AS n FROM care_tasks t JOIN admissions a ON a.id = t.admission_id
                WHERE t.organization_id = ? AND t.status IN ('OPEN','IN_PROGRESS')""" + w2 + " GROUP BY t.task_type ORDER BY n DESC",
                (rs, i) -> new NamedCount(rs.getString("name"), rs.getLong("n")), a2.toArray());
        int grace;
        try {
            grace = settings.getInt(s.org(), SettingsService.MED_OVERDUE_GRACE_MINUTES);
        } catch (RuntimeException e) {
            grace = 30;
        }
        List<Object> a3 = new ArrayList<>(List.of(s.org(), Timestamp.from(Instant.now().minus(grace, ChronoUnit.MINUTES))));
        String w3 = s.admission("a", a3);
        Long overdueDoses = jdbc.queryForObject("""
                SELECT count(*) FROM medication_administrations m JOIN admissions a ON a.id = m.admission_id
                WHERE m.organization_id = ? AND m.status = 'SCHEDULED' AND m.scheduled_at < ?
                  AND a.status IN ('ADMITTED','DISCHARGE_IN_PROGRESS')""" + w3, Long.class, a3.toArray());
        List<Object> a4 = new ArrayList<>(List.of(s.org(), s.from(), s.to()));
        String w4 = s.admission("a", a4);
        List<NamedCount> exceptions = jdbc.query("""
                SELECT m.status AS name, count(*) AS n FROM medication_administrations m JOIN admissions a ON a.id = m.admission_id
                WHERE m.organization_id = ? AND m.scheduled_at >= ? AND m.scheduled_at < ?
                  AND m.status IN ('DELAYED','REFUSED','MISSED','HELD')""" + w4 + " GROUP BY m.status ORDER BY n DESC",
                (rs, i) -> new NamedCount(rs.getString("name"), rs.getLong("n")), a4.toArray());
        List<Object> a5 = new ArrayList<>(List.of(s.org()));
        String w5 = s.admission("a", a5);
        Long followUpsOpen = jdbc.queryForObject("""
                SELECT count(*) FROM medication_administrations m JOIN admissions a ON a.id = m.admission_id
                WHERE m.organization_id = ? AND m.follow_up_status IN ('OPEN','IN_PROGRESS')""" + w5, Long.class, a5.toArray());
        return new TaskSummary(((Number) open.get("open")).longValue(), ((Number) open.get("overdue")).longValue(), byType,
                overdueDoses == null ? 0 : overdueDoses, exceptions, followUpsOpen == null ? 0 : followUpsOpen, grace);
    }

    private List<TransferRow> transfers(Scope s) {
        List<Object> args = new ArrayList<>(List.of(s.org(), s.from(), s.to()));
        String where = s.admission("a", args);
        return jdbc.query("""
                SELECT t.id, t.requested_at, a.admission_number, t.status, t.priority, t.completed_at,
                       fw.name AS from_ward, fr.room_number AS from_room, fb.bed_number AS from_bed,
                       COALESCE(tw.name, tw2.name) AS to_ward, tr.room_number AS to_room, tb.bed_number AS to_bed
                FROM transfers t
                JOIN admissions a ON a.id = t.admission_id
                LEFT JOIN beds fb ON fb.id = t.from_bed_id LEFT JOIN rooms fr ON fr.id = fb.room_id LEFT JOIN wards fw ON fw.id = fb.ward_id
                LEFT JOIN beds tb ON tb.id = t.to_bed_id LEFT JOIN rooms tr ON tr.id = tb.room_id LEFT JOIN wards tw ON tw.id = tb.ward_id
                LEFT JOIN wards tw2 ON tw2.id = t.to_ward_id
                WHERE t.organization_id = ? AND t.requested_at >= ? AND t.requested_at < ?""" + where + " ORDER BY t.requested_at DESC LIMIT 200", (rs, i) -> {
            Instant req = rs.getTimestamp("requested_at").toInstant();
            Timestamp done = rs.getTimestamp("completed_at");
            Double hours = done == null ? null : round1((done.toInstant().toEpochMilli() - req.toEpochMilli()) / 3_600_000.0);
            return new TransferRow(rs.getObject("id", UUID.class), req, rs.getString("admission_number"),
                    location(rs.getString("from_ward"), rs.getString("from_room"), rs.getString("from_bed")),
                    location(rs.getString("to_ward"), rs.getString("to_room"), rs.getString("to_bed")),
                    rs.getString("status"), rs.getString("priority"), done == null ? null : done.toInstant(), hours);
        }, args.toArray());
    }

    private static String location(String ward, String room, String bed) {
        if (ward == null) {
            return null;
        }
        return room == null ? ward : ward + " · " + room + "-" + bed;
    }

    private FollowUpSummary followUps(Scope s) {
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), s.org(), s.from(),
                Timestamp.from(s.toDate().plusDays(1).atStartOfDay(s.zone()).toInstant())));
        String where = (s.departmentId() != null || s.wardId() != null)
                ? " AND f.admission_id IS NOT NULL AND EXISTS (SELECT 1 FROM admissions a WHERE a.id = f.admission_id" + s.admission("a", args) + ")"
                : "";
        Map<String, Object> m = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE f.scheduled_at < ? AND f.status <> 'CANCELLED') AS due,
                       count(*) FILTER (WHERE f.status = 'COMPLETED') AS completed,
                       count(*) FILTER (WHERE f.status = 'MISSED') AS missed,
                       count(*) FILTER (WHERE f.status = 'CANCELLED') AS cancelled,
                       count(*) FILTER (WHERE f.scheduled_at >= ? AND f.status IN ('PLANNED','SCHEDULED')) AS upcoming
                FROM follow_ups f
                WHERE f.organization_id = ? AND f.scheduled_at >= ? AND f.scheduled_at < ?""" + where, args.toArray());
        long due = ((Number) m.get("due")).longValue();
        long completed = ((Number) m.get("completed")).longValue();
        return new FollowUpSummary(((Number) m.get("total")).longValue(), due, completed, ((Number) m.get("missed")).longValue(),
                ((Number) m.get("cancelled")).longValue(), ((Number) m.get("upcoming")).longValue(),
                due == 0 ? null : round1(Math.min(100.0, completed * 100.0 / due)));
    }

    private static List<Definition> operationalDefinitions(int grace) {
        return List.of(
                new Definition("Admissions", "Admissions with an admission time inside the period (cancelled admissions excluded)."),
                new Definition("Discharges", "Admissions whose discharge was completed inside the period."),
                new Definition("Occupied beds (daily)", "Midnight census: beds holding an open bed assignment at the end of each day (current time for today)."),
                new Definition("Occupancy %", "Occupied beds ÷ active beds in scope × 100. Average occupancy is the mean of the daily values."),
                new Definition("Average length of stay", "Mean of (discharge time - admission time) in days, for discharges inside the period."),
                new Definition("Average cleaning time", "Mean minutes a bed stayed in CLEANING before its next status change, for cleaning periods starting inside the period."),
                new Definition("Bed turnover per bed", "Bed releases (discharges, transfers out, corrections) inside the period ÷ active beds in scope."),
                new Definition("Discharge step hours", "Mean hours from discharge workflow initiation to completion of each checklist step, for workflows started inside the period."),
                new Definition("Hours beyond planned discharge", "Mean of (actual departure - planned discharge time) for completed workflows with a planned time; negative means earlier than planned."),
                new Definition("Overdue medication doses", "Current snapshot: scheduled doses for open admissions not recorded more than " + grace + " minutes after their scheduled time."),
                new Definition("Medication exceptions", "Doses scheduled inside the period recorded as delayed, refused, missed or held."),
                new Definition("Overdue care tasks", "Current snapshot: open or in-progress care tasks past their due time."),
                new Definition("Follow-up completion rate", "Completed ÷ follow-ups scheduled inside the period whose time has passed (cancelled excluded) × 100."),
                new Definition("Department / ward filter", "Admission metrics include admissions of the department or that occupied a bed in the ward; bed metrics include beds in the selected ward or in wards belonging to the department."));
    }

    // ================================================================== clinical quality

    @Transactional(readOnly = true)
    public ClinicalQualityReport clinicalQuality(LocalDate from, LocalDate to, UUID departmentId, UUID wardId) {
        Scope s = scope(from, to, departmentId, wardId);
        Timestamp now = Timestamp.from(Instant.now());

        List<Object> a1 = new ArrayList<>(List.of(s.from(), s.to(), now, s.org()));
        String w1 = s.admission("a", a1);
        Map<String, Object> inv = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE l.ordered_at >= ? AND l.ordered_at < ?) AS ordered,
                       count(*) FILTER (WHERE l.status IN ('ORDERED','COLLECTED','IN_PROGRESS')) AS outstanding,
                       count(*) FILTER (WHERE l.status = 'RESULTED') AS awaiting_review,
                       count(*) FILTER (WHERE l.status IN ('ORDERED','COLLECTED','IN_PROGRESS') AND l.due_at < ?) AS overdue
                FROM lab_orders l JOIN admissions a ON a.id = l.admission_id
                WHERE l.organization_id = ?""" + w1, a1.toArray());
        List<Object> a2 = new ArrayList<>(List.of(s.org()));
        String w2 = s.admission("a", a2);
        List<NamedCount> byPriority = jdbc.query("""
                SELECT l.priority AS name, count(*) AS n FROM lab_orders l JOIN admissions a ON a.id = l.admission_id
                WHERE l.organization_id = ? AND l.status IN ('ORDERED','COLLECTED','IN_PROGRESS','RESULTED')""" + w2
                + " GROUP BY l.priority ORDER BY n DESC", (rs, i) -> new NamedCount(rs.getString("name"), rs.getLong("n")), a2.toArray());
        List<Object> a3 = new ArrayList<>(List.of(s.org(), s.from(), s.to()));
        String w3 = s.admission("a", a3);
        BigDecimal tat = jdbc.queryForObject("""
                SELECT avg(extract(epoch FROM (r.first_result - l.ordered_at)) / 3600.0) FROM lab_orders l
                JOIN admissions a ON a.id = l.admission_id
                JOIN LATERAL (SELECT min(resulted_at) AS first_result FROM lab_results lr WHERE lr.lab_order_id = l.id) r ON r.first_result IS NOT NULL
                WHERE l.organization_id = ? AND l.ordered_at >= ? AND l.ordered_at < ?""" + w3, BigDecimal.class, a3.toArray());
        InvestigationSummary investigations = new InvestigationSummary(((Number) inv.get("ordered")).longValue(),
                ((Number) inv.get("outstanding")).longValue(), ((Number) inv.get("awaiting_review")).longValue(),
                ((Number) inv.get("overdue")).longValue(), byPriority, nullableRound1(tat));

        int interval;
        try {
            interval = settings.getInt(s.org(), SettingsService.VITALS_EXPECTED_INTERVAL_HOURS);
        } catch (RuntimeException e) {
            interval = 6;
        }
        List<Object> a4 = new ArrayList<>(List.of(Timestamp.from(Instant.now().minus(interval, ChronoUnit.HOURS)), s.org()));
        String w4 = s.admission("a", a4);
        Map<String, Object> doc = jdbc.queryForMap("""
                SELECT count(*) AS open,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM clinical_notes n WHERE n.admission_id = a.id
                            AND n.note_type = 'ADMISSION_ASSESSMENT' AND n.status <> 'ENTERED_IN_ERROR')) AS assessment,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM treatment_plans p WHERE p.admission_id = a.id AND p.status = 'ACTIVE')) AS plan,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM diagnoses d WHERE d.admission_id = a.id AND d.is_primary AND d.status = 'ACTIVE')) AS diagnosis,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM vital_signs v WHERE v.admission_id = a.id AND NOT v.entered_in_error AND v.recorded_at >= ?)) AS vitals
                FROM admissions a WHERE a.organization_id = ? AND a.status IN ('ADMITTED','DISCHARGE_IN_PROGRESS')""" + w4, a4.toArray());
        List<Object> a5 = new ArrayList<>(List.of(s.org(), s.from(), s.to()));
        String w5 = s.admission("a", a5);
        Map<String, Object> summaries = jdbc.queryForMap("""
                SELECT count(*) AS discharged,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM discharge_summaries ds WHERE ds.admission_id = a.id AND ds.status IN ('FINAL','AMENDED'))) AS final
                FROM admissions a WHERE a.organization_id = ? AND a.status = 'DISCHARGED' AND a.discharged_at >= ? AND a.discharged_at < ?""" + w5,
                a5.toArray());
        long open = ((Number) doc.get("open")).longValue();
        long discharged = ((Number) summaries.get("discharged")).longValue();
        List<CompletenessRow> documentation = List.of(
                completeness("Admission assessment documented (open admissions)", open, doc.get("assessment")),
                completeness("Active treatment plan (open admissions)", open, doc.get("plan")),
                completeness("Primary diagnosis recorded (open admissions)", open, doc.get("diagnosis")),
                completeness("Vital signs within the last " + interval + " h (open admissions)", open, doc.get("vitals")),
                completeness("Final discharge summary (discharges in period)", discharged, summaries.get("final")));

        List<Object> a6 = new ArrayList<>(List.of(s.from(), s.to(), s.org()));
        String w6 = s.admission("a", a6);
        Map<String, Object> alerts = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE c.raised_at >= ? AND c.raised_at < ?) AS raised,
                       count(*) FILTER (WHERE c.status <> 'RESOLVED') AS open
                FROM clinical_alerts c JOIN admissions a ON a.id = c.admission_id
                WHERE c.organization_id = ?""" + w6, a6.toArray());
        List<Object> a7 = new ArrayList<>(List.of(s.org(), s.from(), s.to()));
        String w7 = s.admission("a", a7);
        BigDecimal ack = jdbc.queryForObject("""
                SELECT avg(extract(epoch FROM (c.acknowledged_at - c.raised_at)) / 60.0) FROM clinical_alerts c
                JOIN admissions a ON a.id = c.admission_id
                WHERE c.organization_id = ? AND c.raised_at >= ? AND c.raised_at < ? AND c.acknowledged_at IS NOT NULL""" + w7,
                BigDecimal.class, a7.toArray());
        List<NamedCount> bySeverity = groupCount("c.severity", "clinical_alerts c JOIN admissions a ON a.id = c.admission_id",
                "c.organization_id = ? AND c.raised_at >= ? AND c.raised_at < ?", s, "a");
        List<NamedCount> byType = groupCount("c.alert_type", "clinical_alerts c JOIN admissions a ON a.id = c.admission_id",
                "c.organization_id = ? AND c.raised_at >= ? AND c.raised_at < ?", s, "a");
        List<Object> a8 = new ArrayList<>(List.of(s.org(), s.from(), s.to()));
        String w8 = s.departmentId() != null || s.wardId() != null
                ? " AND i.admission_id IS NOT NULL AND EXISTS (SELECT 1 FROM admissions a WHERE a.id = i.admission_id" + s.admission("a", a8) + ")"
                : "";
        List<NamedCount> incidents = jdbc.query("""
                SELECT i.severity AS name, count(*) AS n FROM incident_reports i
                WHERE i.organization_id = ? AND i.occurred_at >= ? AND i.occurred_at < ?""" + w8 + " GROUP BY i.severity ORDER BY n DESC",
                (rs, i) -> new NamedCount(rs.getString("name"), rs.getLong("n")), a8.toArray());
        SafetyAlertSummary safety = new SafetyAlertSummary(((Number) alerts.get("raised")).longValue(),
                ((Number) alerts.get("open")).longValue(), nullableRound1(ack), bySeverity, byType, incidents);

        List<Definition> defs = List.of(
                new Definition("Outstanding investigations", "Current snapshot: orders that are ordered, collected or in progress (no result yet)."),
                new Definition("Awaiting review", "Current snapshot: orders with results entered but not yet reviewed by a clinician."),
                new Definition("Overdue investigations", "Outstanding orders past their due time."),
                new Definition("Turnaround time", "Mean hours from order to first result, for orders placed inside the period."),
                new Definition("Documentation completeness", "Share of open admissions (or discharges in the period) with the documented item. Entered-in-error records are excluded."),
                new Definition("Safety alerts", "Clinical alerts (threshold, escalation, medication, allergy, critical result) raised inside the period; acknowledgement time is mean minutes from raise to acknowledgement."),
                new Definition("Incidents by severity", "Safety incident reports with an occurrence time inside the period."));
        return new ClinicalQualityReport(Instant.now(), filter(s), investigations, documentation, safety, defs);
    }

    private List<NamedCount> groupCount(String column, String from, String where, Scope s, String admissionAlias) {
        List<Object> args = new ArrayList<>(List.of(s.org(), s.from(), s.to()));
        String scope = s.admission(admissionAlias, args);
        return jdbc.query("SELECT " + column + " AS name, count(*) AS n FROM " + from + " WHERE " + where + scope
                + " GROUP BY " + column + " ORDER BY n DESC", (rs, i) -> new NamedCount(rs.getString("name"), rs.getLong("n")), args.toArray());
    }

    private static CompletenessRow completeness(String item, long applicable, Object complete) {
        long c = complete == null ? 0 : ((Number) complete).longValue();
        return new CompletenessRow(item, applicable, c, applicable == 0 ? null : AdminDashboardService.percent(c, applicable));
    }

    static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    static Double nullableRound1(BigDecimal v) {
        return v == null ? null : round1(v.doubleValue());
    }
}
