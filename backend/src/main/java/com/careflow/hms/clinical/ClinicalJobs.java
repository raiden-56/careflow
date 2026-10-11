package com.careflow.hms.clinical;

import com.careflow.hms.medication.MarService;
import com.careflow.hms.org.Organization;
import com.careflow.hms.org.OrganizationRepository;
import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Function;

/**
 * Background jobs for the clinical module. Each runs per active organization inside its own
 * transaction so a failure in one hospital never blocks another.
 * <ul>
 *   <li>MAR schedule maintenance: keep the dose horizon filled, complete expired orders, cancel doses of inactive orders.</li>
 *   <li>Medication overdue scan: flag overdue doses and escalate per MEDICATION_OVERDUE rules.</li>
 *   <li>Escalation scan: re-notify unacknowledged critical alerts and unacknowledged handovers.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClinicalJobs {

    private final OrganizationRepository organizations;
    private final TransactionTemplate tx;
    private final MarService mar;
    private final AlertService alerts;
    private final HandoverService handovers;

    @Value("${careflow.jobs.clinical-enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${careflow.jobs.medication-schedule:PT10M}", initialDelayString = "PT20S")
    public void maintainSchedules() {
        forEachOrg("medication-schedule", mar::maintainSchedules);
    }

    @Scheduled(fixedDelayString = "${careflow.jobs.medication-overdue-scan:PT1M}", initialDelayString = "PT30S")
    public void medicationOverdue() {
        forEachOrg("medication-overdue", mar::escalateOverdue);
    }

    @Scheduled(fixedDelayString = "${careflow.jobs.clinical-escalation-scan:PT2M}", initialDelayString = "PT45S")
    public void escalations() {
        forEachOrg("alert-escalation", alerts::escalateUnacknowledged);
        forEachOrg("handover-escalation", handovers::escalateUnacknowledged);
    }

    private void forEachOrg(String job, Function<UUID, Integer> work) {
        if (!enabled) {
            return;
        }
        for (Organization org : organizations.findByPlatformFalseAndStatus(Organization.Status.ACTIVE)) {
            try {
                Integer n = TenantContext.runAs(org.getId(), null, () -> tx.execute(status -> work.apply(org.getId())));
                if (n != null && n > 0) {
                    log.info("Job {} for {}: {} change(s)", job, org.getCode(), n);
                }
            } catch (RuntimeException e) {
                log.error("Job {} failed for organization {}", job, org.getCode(), e);
            }
        }
    }
}
