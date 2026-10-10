package com.careflow.hms.notification;

import com.careflow.hms.admission.AdmissionCareTeamRepository;
import com.careflow.hms.admission.AdmissionRepository;
import com.careflow.hms.admission.BedAssignmentRepository;
import com.careflow.hms.facility.BedRepository;
import com.careflow.hms.facility.StaffWardAssignmentRepository;
import com.careflow.hms.patient.PatientAccessGrantRepository;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Role-aware notifications: persisted in-app notifications (with read/ack state and dedupe),
 * best-effort real-time push, and optional external channels through {@link ExternalChannelGateway}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final UserRepository users;
    private final PatientRepository patients;
    private final PatientAccessGrantRepository grants;
    private final AdmissionRepository admissions;
    private final AdmissionCareTeamRepository careTeam;
    private final BedAssignmentRepository bedAssignments;
    private final BedRepository beds;
    private final StaffWardAssignmentRepository wardAssignments;
    private final ExternalChannelGateway gateway;
    private final SseHub sseHub;

    public record NotificationView(UUID id, String category, Notification.Priority priority, String title,
                                   String message, String link, String entityType, UUID entityId,
                                   boolean requiresAck, Instant readAt, Instant acknowledgedAt, Instant createdAt) {
        static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getCategory(), n.getPriority(), n.getTitle(), n.getMessage(),
                    n.getLink(), n.getEntityType(), n.getEntityId(), n.isRequiresAck(), n.getReadAt(),
                    n.getAcknowledgedAt(), n.getCreatedAt());
        }
    }

    /** Sends to explicit users. Returns the number of notifications created (after dedupe). */
    @Transactional
    public int notifyUsers(Collection<UUID> userIds, NotificationRequest req) {
        int created = 0;
        UUID orgId = TenantContext.requireOrganizationId();
        for (UUID userId : new LinkedHashSet<>(userIds)) {
            if (userId == null) {
                continue;
            }
            if (req.dedupeKey() != null && notifications.existsByRecipientUserIdAndDedupeKey(userId, req.dedupeKey())) {
                continue;
            }
            Notification n = new Notification();
            n.setOrganizationId(orgId);
            n.setRecipientUserId(userId);
            n.setCategory(req.category());
            n.setPriority(req.priority() == null ? Notification.Priority.NORMAL : req.priority());
            n.setTitle(req.title());
            n.setMessage(req.message());
            n.setLink(req.link());
            n.setEntityType(req.entityType());
            n.setEntityId(req.entityId());
            n.setDedupeKey(req.dedupeKey());
            n.setRequiresAck(req.requiresAck());
            notifications.save(n);
            recordDelivery(n, NotificationDelivery.Channel.IN_APP, "in-app", null,
                    new ExternalChannelGateway.Result(NotificationDelivery.Status.DELIVERED, "in-app", null));
            if (req.channels() != null) {
                User u = users.findById(userId).orElse(null);
                for (NotificationDelivery.Channel ch : req.channels()) {
                    if (ch == NotificationDelivery.Channel.IN_APP || u == null) {
                        continue;
                    }
                    String dest = ch == NotificationDelivery.Channel.EMAIL ? u.getEmail() : u.getPhone();
                    ExternalChannelGateway.Result r;
                    try {
                        r = gateway.send(orgId, ch, dest, req.title(), req.message());
                    } catch (RuntimeException ex) {
                        log.warn("External delivery failed on {}", ch, ex);
                        r = new ExternalChannelGateway.Result(NotificationDelivery.Status.FAILED, "unknown", ex.getMessage());
                    }
                    recordDelivery(n, ch, r.provider(), dest, r);
                }
            }
            pushAfterCommit(userId, NotificationView.of(n));
            created++;
        }
        return created;
    }

    @Transactional
    public int notifyRole(Role role, NotificationRequest req) {
        UUID orgId = TenantContext.requireOrganizationId();
        List<UUID> ids = users.findByOrganizationIdAndRoleAndStatus(orgId, role, User.Status.ACTIVE)
                .stream().map(User::getId).toList();
        return notifyUsers(ids, req);
    }

    /** Attending doctor + active care team of an admission (optionally restricted to a role). */
    @Transactional
    public int notifyCareTeam(UUID admissionId, Role onlyRole, NotificationRequest req) {
        Set<UUID> ids = new LinkedHashSet<>();
        admissions.findById(admissionId).ifPresent(a -> {
            if (onlyRole == null || onlyRole == Role.DOCTOR) {
                ids.add(a.getAttendingDoctorId());
            }
        });
        careTeam.findByAdmissionIdAndRemovedAtIsNull(admissionId).forEach(t -> ids.add(t.getUserId()));
        if (onlyRole != null) {
            Map<UUID, Role> roles = new java.util.HashMap<>();
            users.findAllById(ids).forEach(u -> roles.put(u.getId(), u.getRole()));
            ids.removeIf(id -> roles.get(id) != onlyRole);
        }
        return notifyUsers(ids, req);
    }

    /** Nurses assigned to the ward where the admission currently has a bed. */
    @Transactional
    public int notifyWardNurses(UUID admissionId, NotificationRequest req) {
        return bedAssignments.findByAdmissionIdAndReleasedAtIsNull(admissionId)
                .flatMap(ba -> beds.findById(ba.getBedId()))
                .map(b -> notifyUsers(wardAssignments.activeUserIdsForWard(b.getWardId()), req))
                .orElse(0);
    }

    /** The patient's own portal login plus caregivers holding an active grant. */
    @Transactional
    public int notifyPatient(UUID patientId, NotificationRequest req) {
        Set<UUID> ids = new LinkedHashSet<>();
        patients.findById(patientId).ifPresent(p -> {
            if (p.getUserId() != null) {
                ids.add(p.getUserId());
            }
        });
        Instant now = Instant.now();
        grants.findByPatientIdOrderByGrantedAtDesc(patientId).stream()
                .filter(g -> g.isActive(now))
                .forEach(g -> ids.add(g.getGranteeUserId()));
        return notifyUsers(ids, req);
    }

    private void recordDelivery(Notification n, NotificationDelivery.Channel ch, String provider, String dest,
                                ExternalChannelGateway.Result r) {
        NotificationDelivery d = new NotificationDelivery();
        d.setNotificationId(n.getId());
        d.setChannel(ch);
        d.setProvider(r.provider() == null ? provider : r.provider());
        d.setDestination(dest);
        d.setStatus(r.status());
        d.setErrorMessage(r.error());
        deliveries.save(d);
    }

    private void pushAfterCommit(UUID userId, NotificationView view) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sseHub.push(userId, "notification", view);
                }
            });
        } else {
            sseHub.push(userId, "notification", view);
        }
    }
}
