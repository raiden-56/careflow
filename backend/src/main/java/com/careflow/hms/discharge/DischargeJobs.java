package com.careflow.hms.discharge;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.notification.EscalationRule;
import com.careflow.hms.notification.EscalationRuleRepository;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.org.Organization;
import com.careflow.hms.org.OrganizationRepository;
import com.careflow.hms.org.SettingsService;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Background work for discharge continuity:
 * <ul>
 *   <li>re-evaluates automatic checks of open workflows (data changed by other modules),</li>
 *   <li>escalates steps that stay BLOCKED beyond the hospital's DISCHARGE_BLOCKED rule (deduplicated),</li>
 *   <li>sends follow-up reminders {@code followUp.reminderHoursBefore} ahead of the appointment.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DischargeJobs {

    private final OrganizationRepository organizations;
    private final DischargeWorkflowRepository workflows;
    private final DischargeStepRepository steps;
    private final AdmissionRepository admissions;
    private final PatientRepository patients;
    private final EscalationRuleRepository rules;
    private final DischargeWorkflowService workflowService;
    private final FollowUpService followUpService;
    private final NotificationService notifications;
    private final TimelineService timeline;
    private final SettingsService settings;
    private final TransactionTemplate tx;

    @Scheduled(fixedDelayString = "${careflow.jobs.discharge-scan:PT5M}", initialDelayString = "${careflow.jobs.discharge-initial-delay:PT2M}")
    public void dischargeScan() {
        for (Organization org : organizations.findByPlatformFalseAndStatus(Organization.Status.ACTIVE)) {
            try {
                syncOpenWorkflows(org.getId());
                escalateBlocked(org.getId());
            } catch (RuntimeException e) {
                log.warn("Discharge scan failed for organization {}", org.getId(), e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${careflow.jobs.follow-up-reminder-scan:PT5M}", initialDelayString = "${careflow.jobs.discharge-initial-delay:PT2M}")
    public void followUpReminders() {
        for (Organization org : organizations.findByPlatformFalseAndStatus(Organization.Status.ACTIVE)) {
            try {
                sendReminders(org.getId());
            } catch (RuntimeException e) {
                log.warn("Follow-up reminder scan failed for organization {}", org.getId(), e);
            }
        }
    }

    public void syncOpenWorkflows(UUID orgId) {
        List<UUID> ids = TenantContext.runAs(orgId, null, () -> workflows.findByOrganizationIdAndStatusIn(orgId,
                EnumSet.of(DischargeWorkflow.Status.IN_PROGRESS, DischargeWorkflow.Status.READY)).stream().map(DischargeWorkflow::getId).toList());
        for (UUID id : ids) {
            try {
                TenantContext.runAs(orgId, null, () -> tx.executeWithoutResult(s -> workflowService.syncWorkflow(id)));
            } catch (RuntimeException e) {
                log.debug("Could not re-evaluate discharge workflow {}: {}", id, e.getMessage());
            }
        }
    }

    public int sendReminders(UUID orgId) {
        return TenantContext.runAs(orgId, null, () -> tx.execute(s ->
                followUpService.sendDueReminders(orgId, settings.getInt(orgId, SettingsService.FOLLOWUP_REMINDER_HOURS))));
    }

    /**
     * Notifies the rule's role about steps blocked longer than the configured threshold. One notification per
     * step, rule level and blocking episode (the dedupe key includes the time the step became blocked).
     *
     * @return number of notifications created
     */
    public int escalateBlocked(UUID orgId) {
        return TenantContext.runAs(orgId, null, () -> tx.execute(s -> {
            List<EscalationRule> active = rules.findByOrganizationIdAndEventTypeAndActiveTrueOrderByEscalationLevel(
                    orgId, EscalationRule.EventType.DISCHARGE_BLOCKED);
            if (active.isEmpty()) {
                return 0;
            }
            Instant now = Instant.now();
            int created = 0;
            for (DischargeStep step : steps.findBlockedInOpenWorkflows(orgId)) {
                DischargeWorkflow w = workflows.findById(step.getWorkflowId()).orElse(null);
                Admission a = w == null ? null : admissions.findById(w.getAdmissionId()).orElse(null);
                if (a == null) {
                    continue;
                }
                long blockedMinutes = Duration.between(step.getUpdatedAt(), now).toMinutes();
                for (EscalationRule rule : active) {
                    if (blockedMinutes < rule.getThresholdMinutes()) {
                        continue;
                    }
                    String key = "DISCHARGE_BLOCKED:" + step.getId() + ":L" + rule.getEscalationLevel() + ":" + step.getUpdatedAt().getEpochSecond();
                    String patient = patients.findById(a.getPatientId()).map(p -> p.fullName()).orElse("Patient");
                    Notification.Priority priority;
                    try {
                        priority = Notification.Priority.valueOf(rule.getPriority());
                    } catch (IllegalArgumentException e) {
                        priority = Notification.Priority.HIGH;
                    }
                    int n = notifications.notifyRole(rule.getEscalateToRole(), DischargeWorkflowService.req("DISCHARGE", priority,
                            "Discharge blocked " + hours(blockedMinutes) + ": " + patient,
                            a.getAdmissionNumber() + " - " + step.getName() + ": "
                                    + (step.getBlockerReason() == null ? "blocked" : DischargeWorkflowService.truncate(step.getBlockerReason(), 200))
                                    + ". Owner: " + DischargeWorkflowService.roleLabel(step.getResponsibleRole()) + " team.",
                            DischargeWorkflowService.linkFor(rule.getEscalateToRole(), a.getId()), w.getId(), key));
                    if (n > 0) {
                        created += n;
                        timeline.record(TimelineService.Entry.builder()
                                .patientId(a.getPatientId()).admissionId(a.getId())
                                .eventType("DISCHARGE_BLOCKER_ESCALATED").category(TimelineEvent.Category.DISCHARGE)
                                .title("Discharge blocker escalated (level " + rule.getEscalationLevel() + ")")
                                .detail(step.getName() + " blocked for " + hours(blockedMinutes))
                                .status("BLOCKED").visibility(TimelineEvent.Visibility.INTERNAL)
                                .entityType("DISCHARGE_STEP").entityId(step.getId()).build());
                    }
                }
            }
            return created;
        }));
    }

    private static String hours(long minutes) {
        return minutes < 120 ? minutes + " min" : (minutes / 60) + " h";
    }
}
