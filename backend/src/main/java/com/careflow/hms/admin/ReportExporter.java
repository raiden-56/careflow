package com.careflow.hms.admin;

import com.careflow.hms.admin.ReportDtos.ClinicalQualityReport;
import com.careflow.hms.admin.ReportDtos.Definition;
import com.careflow.hms.admin.ReportDtos.OperationalReport;
import com.careflow.hms.admin.ReportDtos.ReportFilter;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Renders report payloads as CSV (sectioned) or PDF (OpenPDF). Uses only the already-computed values. */
@Component
public class ReportExporter {

    public record Section(String title, List<String> headers, List<List<Object>> rows) {
    }

    // ------------------------------------------------------------------ section builders

    public List<Section> sections(OperationalReport r) {
        List<Section> s = new ArrayList<>();
        var sm = r.summary();
        s.add(new Section("Summary", List.of("Metric", "Value"), List.of(
                row("Admissions", sm.admissions()), row("Discharges", sm.discharges()),
                row("Average length of stay (days)", sm.averageLengthOfStayDays()),
                row("Average occupancy (%)", sm.averageOccupancyPercent()), row("Active beds in scope", sm.bedsInScope()),
                row("Average cleaning time (min)", sm.averageCleaningMinutes()), row("Bed turnover per bed", sm.bedTurnoverPerBed()),
                row("Transfers requested", sm.transfersRequested()), row("Transfers completed", sm.transfersCompleted()),
                row("Follow-up completion (%)", sm.followUpCompletionPercent()),
                row("Overdue medication doses (now)", sm.overdueMedicationDoses()), row("Medication exceptions", sm.medicationExceptions()),
                row("Open care tasks (now)", sm.openCareTasks()), row("Overdue care tasks (now)", sm.overdueCareTasks()))));
        s.add(new Section("Daily admissions, discharges and occupancy", List.of("Date", "Admissions", "Discharges", "Occupied beds", "Occupancy %"),
                r.daily().stream().map(d -> row(d.date(), d.admissions(), d.discharges(), d.occupiedBeds(), d.occupancyPercent())).toList()));
        s.add(new Section("Ward distribution and turnover", List.of("Ward", "Beds", "Occupied now", "Occupancy now %", "Bed assignments", "Bed releases", "Avg cleaning (min)", "Turnover per bed"),
                r.wards().stream().map(w -> row(w.wardName(), w.beds(), w.occupiedNow(), w.occupancyNowPercent(), w.bedAssignments(),
                        w.bedReleases(), w.averageCleaningMinutes(), w.turnoverPerBed())).toList()));
        s.add(new Section("Length of stay by department", List.of("Department", "Discharges", "Average days", "Longest stay (days)"),
                r.lengthOfStay().stream().map(l -> row(l.department(), l.discharges(), l.averageDays(), l.maxDays())).toList()));
        var dd = r.discharge();
        List<List<Object>> delayRows = new ArrayList<>(dd.steps().stream().map(d -> row(d.stepName(), d.category(), d.workflows(),
                d.completed(), d.open(), d.blocked(), d.averageHoursToComplete())).toList());
        s.add(new Section("Discharge delays by workflow stage (" + dd.workflowsStarted() + " started, " + dd.workflowsCompleted()
                + " completed; avg " + fmt(dd.averageHoursToDeparture()) + " h to departure)",
                List.of("Step", "Category", "Workflows", "Completed", "Open", "Blocked", "Avg hours to complete"), delayRows));
        var t = r.tasks();
        List<List<Object>> taskRows = new ArrayList<>();
        taskRows.add(row("Open care tasks", t.openCareTasks()));
        taskRows.add(row("Overdue care tasks", t.overdueCareTasks()));
        t.openTasksByType().forEach(c -> taskRows.add(row("Open tasks: " + c.name(), c.count())));
        taskRows.add(row("Overdue medication doses (grace " + t.overdueGraceMinutes() + " min)", t.overdueMedicationDoses()));
        t.medicationExceptions().forEach(c -> taskRows.add(row("Medication exceptions: " + c.name(), c.count())));
        taskRows.add(row("Open exception follow-ups", t.openExceptionFollowUps()));
        s.add(new Section("Pending tasks and medication events", List.of("Item", "Count"), taskRows));
        s.add(new Section("Transfer history", List.of("Requested", "Admission", "From", "To", "Priority", "Status", "Completed", "Turnaround (h)"),
                r.transfers().stream().map(x -> row(x.requestedAt(), x.admissionNumber(), x.fromLocation(), x.toLocation(), x.priority(),
                        x.status(), x.completedAt(), x.turnaroundHours())).toList()));
        var f = r.followUps();
        s.add(new Section("Follow-up completion", List.of("Metric", "Value"), List.of(
                row("Scheduled in period", f.scheduledInRange()), row("Due (time passed)", f.due()), row("Completed", f.completed()),
                row("Missed", f.missed()), row("Cancelled", f.cancelled()), row("Upcoming", f.upcoming()),
                row("Completion rate (%)", f.completionPercent()))));
        s.add(definitions(r.definitions()));
        return s;
    }

    public List<Section> sections(ClinicalQualityReport r) {
        List<Section> s = new ArrayList<>();
        var i = r.investigations();
        List<List<Object>> inv = new ArrayList<>(List.of(row("Ordered in period", i.orderedInRange()), row("Outstanding (no result)", i.outstanding()),
                row("Awaiting review", i.awaitingReview()), row("Overdue", i.overdue()), row("Average turnaround (h)", i.averageTurnaroundHours())));
        i.outstandingByPriority().forEach(c -> inv.add(row("Open by priority: " + c.name(), c.count())));
        s.add(new Section("Investigations", List.of("Metric", "Value"), inv));
        s.add(new Section("Documentation completeness", List.of("Item", "Applicable", "Complete", "Percent"),
                r.documentation().stream().map(d -> row(d.item(), d.applicable(), d.complete(), d.percent())).toList()));
        var sa = r.safety();
        List<List<Object>> safety = new ArrayList<>(List.of(row("Alerts raised in period", sa.raisedInRange()), row("Open alerts now", sa.openNow()),
                row("Average minutes to acknowledge", sa.averageMinutesToAcknowledge())));
        sa.bySeverity().forEach(c -> safety.add(row("Alerts: " + c.name(), c.count())));
        sa.byType().forEach(c -> safety.add(row("Alert type: " + c.name(), c.count())));
        sa.incidentsBySeverity().forEach(c -> safety.add(row("Incidents: " + c.name(), c.count())));
        s.add(new Section("Safety alert history", List.of("Metric", "Value"), safety));
        s.add(definitions(r.definitions()));
        return s;
    }

    private Section definitions(List<Definition> defs) {
        return new Section("Metric definitions", List.of("Metric", "Definition"),
                defs.stream().map(d -> row(d.metric(), d.definition())).toList());
    }

    // ------------------------------------------------------------------ CSV

    public byte[] csv(String title, ReportFilter filter, String hospital, List<Section> sections) {
        StringBuilder sb = new StringBuilder();
        sb.append('﻿');
        line(sb, List.of(title));
        line(sb, List.of("Hospital", hospital));
        line(sb, List.of("Period", filter.from() + " to " + filter.to() + " (" + filter.timezone() + ")"));
        line(sb, List.of("Department", filter.departmentName() == null ? "All" : filter.departmentName()));
        line(sb, List.of("Ward", filter.wardName() == null ? "All" : filter.wardName()));
        line(sb, List.of("Generated", Instant.now().toString()));
        for (Section s : sections) {
            sb.append("\r\n");
            line(sb, List.of(s.title()));
            line(sb, s.headers());
            for (List<Object> r : s.rows()) {
                line(sb, r);
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static void line(StringBuilder sb, List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvCell(cells.get(i)));
        }
        sb.append("\r\n");
    }

    /** Quotes a cell and neutralizes spreadsheet formula injection. */
    public static String csvCell(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") || s.startsWith("'")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ------------------------------------------------------------------ PDF

    public byte[] pdf(String title, ReportFilter filter, String hospital, List<Section> sections) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, new Color(16, 38, 58));
        Font h2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, new Color(15, 118, 110));
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, new Color(74, 91, 108));
        Font cell = FontFactory.getFont(FontFactory.HELVETICA, 8.5f);
        Font head = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, Color.WHITE);
        doc.add(new Paragraph(title, h1));
        ZoneId zone = ZoneId.of(filter.timezone());
        doc.add(new Paragraph(hospital + "  |  Period " + filter.from() + " to " + filter.to()
                + "  |  Department: " + (filter.departmentName() == null ? "All" : filter.departmentName())
                + "  |  Ward: " + (filter.wardName() == null ? "All" : filter.wardName())
                + "  |  Generated " + DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").format(Instant.now().atZone(zone)), small));
        for (Section s : sections) {
            Paragraph p = new Paragraph(s.title(), h2);
            p.setSpacingBefore(12);
            p.setSpacingAfter(4);
            doc.add(p);
            if (s.rows().isEmpty()) {
                doc.add(new Paragraph("No data for this period.", small));
                continue;
            }
            PdfPTable table = new PdfPTable(s.headers().size());
            table.setWidthPercentage(100);
            table.setHeaderRows(1);
            for (String h : s.headers()) {
                PdfPCell c = new PdfPCell(new Phrase(h, head));
                c.setBackgroundColor(new Color(15, 118, 110));
                c.setPadding(4);
                table.addCell(c);
            }
            boolean alt = false;
            for (List<Object> r : s.rows()) {
                for (Object v : r) {
                    PdfPCell c = new PdfPCell(new Phrase(display(v, zone), cell));
                    c.setPadding(3.5f);
                    if (v instanceof Number) {
                        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    }
                    if (alt) {
                        c.setBackgroundColor(new Color(246, 245, 241));
                    }
                    table.addCell(c);
                }
                alt = !alt;
            }
            doc.add(table);
        }
        doc.close();
        return out.toByteArray();
    }

    private static String display(Object v, ZoneId zone) {
        if (v == null) {
            return "—";
        }
        if (v instanceof Instant i) {
            return DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").format(i.atZone(zone));
        }
        return String.valueOf(v);
    }

    private static String fmt(Double d) {
        return d == null ? "—" : String.valueOf(d);
    }

    private static List<Object> row(Object... values) {
        List<Object> l = new ArrayList<>(values.length);
        java.util.Collections.addAll(l, values);
        return l;
    }
}
