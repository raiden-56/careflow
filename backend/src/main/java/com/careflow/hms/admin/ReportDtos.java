package com.careflow.hms.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Operational and clinical-quality report payloads. Every metric is defined in {@code definitions}. */
public final class ReportDtos {

    private ReportDtos() {
    }

    public record ReportFilter(LocalDate from, LocalDate to, UUID departmentId, String departmentName, UUID wardId,
                               String wardName, String timezone) {
    }

    public record Definition(String metric, String definition) {
    }

    public record NamedCount(String name, long count) {
    }

    public record Summary(long admissions, long discharges, Double averageLengthOfStayDays, double averageOccupancyPercent,
                          long bedsInScope, Double averageCleaningMinutes, double bedTurnoverPerBed, long transfersRequested,
                          long transfersCompleted, Double followUpCompletionPercent, long overdueMedicationDoses,
                          long medicationExceptions, long openCareTasks, long overdueCareTasks) {
    }

    public record DailyRow(LocalDate date, long admissions, long discharges, long occupiedBeds, double occupancyPercent) {
    }

    public record WardRow(UUID wardId, String wardName, long beds, long occupiedNow, double occupancyNowPercent,
                          long bedAssignments, long bedReleases, Double averageCleaningMinutes, double turnoverPerBed) {
    }

    public record LosRow(String department, long discharges, Double averageDays, Double maxDays) {
    }

    public record DelayRow(String stepCode, String stepName, String category, long workflows, long completed, long open,
                           long blocked, Double averageHoursToComplete) {
    }

    public record DischargeDelays(long workflowsStarted, long workflowsCompleted, Double averageHoursToDeparture,
                                  Double averageHoursBeyondPlanned, List<DelayRow> steps) {
    }

    public record TaskSummary(long openCareTasks, long overdueCareTasks, List<NamedCount> openTasksByType,
                              long overdueMedicationDoses, List<NamedCount> medicationExceptions,
                              long openExceptionFollowUps, int overdueGraceMinutes) {
    }

    public record TransferRow(UUID id, Instant requestedAt, String admissionNumber, String fromLocation,
                              String toLocation, String status, String priority, Instant completedAt,
                              Double turnaroundHours) {
    }

    public record FollowUpSummary(long scheduledInRange, long due, long completed, long missed, long cancelled,
                                  long upcoming, Double completionPercent) {
    }

    public record OperationalReport(Instant generatedAt, ReportFilter filter, Summary summary, List<DailyRow> daily,
                                    List<WardRow> wards, List<LosRow> lengthOfStay, DischargeDelays discharge,
                                    TaskSummary tasks, List<TransferRow> transfers, FollowUpSummary followUps,
                                    List<Definition> definitions) {
    }

    public record InvestigationSummary(long orderedInRange, long outstanding, long awaitingReview, long overdue,
                                       List<NamedCount> outstandingByPriority, Double averageTurnaroundHours) {
    }

    public record CompletenessRow(String item, long applicable, long complete, Double percent) {
    }

    public record SafetyAlertSummary(long raisedInRange, long openNow, Double averageMinutesToAcknowledge,
                                     List<NamedCount> bySeverity, List<NamedCount> byType,
                                     List<NamedCount> incidentsBySeverity) {
    }

    public record ClinicalQualityReport(Instant generatedAt, ReportFilter filter, InvestigationSummary investigations,
                                        List<CompletenessRow> documentation, SafetyAlertSummary safety,
                                        List<Definition> definitions) {
    }
}
