package com.careflow.hms.billing;

import com.careflow.hms.org.Organization;
import com.careflow.hms.org.OrganizationRepository;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Nightly posting of the previous day's room and nursing charges for every open invoice of every
 * active hospital. Each invoice runs in its own transaction so one failure cannot block the rest;
 * the work is idempotent, so a missed night is caught up on the next run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomChargeJob {

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final InvoiceRepository invoices;
    private final RoomChargeService roomCharges;
    private final TransactionTemplate tx;

    @Scheduled(cron = "${careflow.jobs.room-charge-cron:0 10 0 * * *}", zone = "Asia/Kolkata")
    public void nightly() {
        for (Organization org : organizations.findByPlatformFalseAndStatus(Organization.Status.ACTIVE)) {
            try {
                runFor(org.getId());
            } catch (RuntimeException e) {
                log.error("Room charge posting failed for organization {}", org.getCode(), e);
            }
        }
    }

    /** Posts outstanding room charges for all open admission invoices of one organization. Returns invoices touched. */
    public int runFor(UUID orgId) {
        UUID actor = systemActor(orgId);
        if (actor == null) {
            log.warn("No active billing or administrator account in organization {}; room charges not posted", orgId);
            return 0;
        }
        return TenantContext.runAs(orgId, actor, () -> {
            List<UUID> ids = invoices.findByOrganizationIdAndStatusIn(orgId, EnumSet.of(Invoice.Status.OPEN)).stream()
                    .filter(i -> i.getAdmissionId() != null).map(Invoice::getId).toList();
            int touched = 0;
            for (UUID id : ids) {
                try {
                    Integer n = tx.execute(status -> {
                        Invoice inv = invoices.lockById(id, orgId).orElse(null);
                        if (inv == null) {
                            return 0;
                        }
                        BillingDtos.RoomChargeResult r = roomCharges.post(inv, false, false);
                        return r.roomDaysPosted() + r.nursingDaysPosted() > 0 ? 1 : 0;
                    });
                    touched += n == null ? 0 : n;
                } catch (RuntimeException e) {
                    log.warn("Room charges for invoice {} failed: {}", id, e.getMessage());
                }
            }
            if (touched > 0) {
                log.info("Nightly room charges posted on {} invoice(s)", touched);
            }
            return touched;
        });
    }

    /** Charges posted by the job are attributed to a billing account (else an administrator). */
    private UUID systemActor(UUID orgId) {
        for (Role role : List.of(Role.BILLING, Role.HOSPITAL_ADMIN)) {
            List<User> found = users.findByOrganizationIdAndRoleAndStatus(orgId, role, User.Status.ACTIVE);
            if (!found.isEmpty()) {
                return found.get(0).getId();
            }
        }
        return null;
    }
}
