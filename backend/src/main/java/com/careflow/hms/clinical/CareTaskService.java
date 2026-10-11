package com.careflow.hms.clinical;

import com.careflow.hms.admission.Admission;
import com.careflow.hms.audit.AuditService;
import com.careflow.hms.clinical.ClinicalDtos.TaskRequest;
import com.careflow.hms.clinical.ClinicalDtos.TaskView;
import com.careflow.hms.clinical.ClinicalSupport.PatientRef;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent;
import com.careflow.hms.timeline.TimelineService;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** Nursing / ward care tasks: create, assign, progress and complete with notes. */
@Service
@RequiredArgsConstructor
public class CareTaskService {

    public static final Set<CareTask.Status> ACTIVE = EnumSet.of(CareTask.Status.OPEN, CareTask.Status.IN_PROGRESS);

    private final CareTaskRepository tasks;
    private final UserRepository users;
    private final ClinicalSupport support;
    private final TimelineService timeline;
    private final NotificationService notifications;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<TaskView> forAdmission(UUID admissionId) {
        support.requireClinical(admissionId);
        return views(tasks.findByAdmissionIdOrderByDueAtAsc(admissionId).stream().sorted(order()).toList());
    }

    /** Task board across the caller's patients; optional ward filter and "assigned to me" scope. */
    @Transactional(readOnly = true)
    public List<TaskView> board(UUID wardId, boolean assignedToMe, Collection<CareTask.Status> statuses) {
        List<UUID> ids = support.myAdmissions().stream()
                .filter(s -> wardId == null || s.location() != null && wardId.equals(s.location().wardId()))
                .map(s -> s.id()).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Collection<CareTask.Status> st = statuses == null || statuses.isEmpty() ? EnumSet.allOf(CareTask.Status.class) : statuses;
        UUID me = TenantContext.actorId();
        Instant cutoff = Instant.now().minusSeconds(24 * 3600);
        List<CareTask> list = tasks.findByAdmissionIdInAndStatusInOrderByDueAtAsc(ids, st).stream()
                .filter(t -> !assignedToMe || me.equals(t.getAssignedTo()))
                // Completed/cancelled tasks are shown for the last 24 h only.
                .filter(t -> ACTIVE.contains(t.getStatus()) || t.getCompletedAt() == null || t.getCompletedAt().isAfter(cutoff))
                .sorted(order()).toList();
        return views(list);
    }

    private static Comparator<CareTask> order() {
        return Comparator.<CareTask, Integer>comparing(t -> ACTIVE.contains(t.getStatus()) ? 0 : 1)
                .thenComparing(t -> -t.getPriority().ordinal())
                .thenComparing(t -> t.getDueAt() == null ? Instant.MAX : t.getDueAt());
    }

    @Transactional
    public TaskView create(UUID admissionId, TaskRequest r) {
        Admission a = support.requireOpenClinical(admissionId);
        if (r.dueAt() != null && r.dueAt().isBefore(Instant.now().minusSeconds(3600))) {
            throw ApiException.badRequest("INVALID_DUE", "The due time cannot be in the past.");
        }
        UUID assignee = r.assignedTo() == null ? null : requireAssignee(r.assignedTo()).getId();
        CareTask t = createTask(a, r.taskType(), r.title().trim(), ClinicalSupport.blankToNull(r.description()),
                r.priority() == null ? CareTask.Priority.NORMAL : r.priority(), r.dueAt(), assignee, Role.NURSE, null, null);
        audit.record("CARE_TASK_CREATED", "CARE_TASK", t.getId(), a.getPatientId(), t.getTitle());
        return views(List.of(t)).get(0);
    }

    /** Creates a task (also used by other workflows, e.g. specimen collection for an investigation). */
    @Transactional
    public CareTask createTask(Admission a, CareTask.TaskType type, String title, String description, CareTask.Priority priority,
                               Instant dueAt, UUID assignedTo, Role assignedRole, String sourceType, UUID sourceId) {
        CareTask t = new CareTask();
        t.setAdmissionId(a.getId());
        t.setPatientId(a.getPatientId());
        t.setTaskType(type);
        t.setTitle(ClinicalSupport.truncate(title, 300));
        t.setDescription(description);
        t.setPriority(priority);
        t.setDueAt(dueAt);
        t.setAssignedTo(assignedTo);
        t.setAssignedRole(assignedRole);
        t.setSourceType(sourceType);
        t.setSourceId(sourceId);
        t.setCreatedBy(TenantContext.actorId());
        t.setCreatedAt(Instant.now());
        t = tasks.saveAndFlush(t);
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("CARE_TASK_CREATED").category(TimelineEvent.Category.NURSING)
                .title("Task: " + t.getTitle()).detail(description).status(t.getPriority().name())
                .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("CARE_TASK").entityId(t.getId()).build());
        NotificationRequest req = NotificationRequest.builder()
                .category("TASK").priority(priority == CareTask.Priority.URGENT ? Notification.Priority.HIGH : Notification.Priority.NORMAL)
                .title("New task: " + t.getTitle()).message("Admission " + a.getAdmissionNumber())
                .link("/nurse/tasks").entityType("CARE_TASK").entityId(t.getId()).dedupeKey("TASK:" + t.getId()).build();
        if (assignedTo != null) {
            notifications.notifyUsers(List.of(assignedTo), req);
        } else if (priority == CareTask.Priority.URGENT || priority == CareTask.Priority.HIGH) {
            notifications.notifyWardNurses(a.getId(), req);
        }
        return t;
    }

    @Transactional
    public TaskView changeStatus(UUID taskId, CareTask.Status status, String notes) {
        CareTask t = support.requireOrg(tasks.findById(taskId), "Task");
        Admission a = support.requireClinical(t.getAdmissionId());
        if (!ACTIVE.contains(t.getStatus())) {
            throw ApiException.rule("TASK_CLOSED", "This task is already " + t.getStatus().name().toLowerCase() + ".");
        }
        if (status == CareTask.Status.DONE) {
            return complete(taskId, notes);
        }
        if (status == CareTask.Status.CANCELLED && (notes == null || notes.trim().length() < 3)) {
            throw ApiException.badRequest("REASON_REQUIRED", "Document why the task is cancelled.");
        }
        t.setStatus(status);
        if (status == CareTask.Status.CANCELLED) {
            t.setCompletedBy(TenantContext.actorId());
            t.setCompletedAt(Instant.now());
            t.setCompletionNotes(notes.trim());
            timeline.record(TimelineService.Entry.builder()
                    .patientId(a.getPatientId()).admissionId(a.getId())
                    .eventType("CARE_TASK_CANCELLED").category(TimelineEvent.Category.NURSING)
                    .title("Task cancelled: " + t.getTitle()).detail(notes).status("CANCELLED")
                    .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("CARE_TASK").entityId(t.getId()).build());
        }
        if (status == CareTask.Status.IN_PROGRESS && t.getAssignedTo() == null) {
            t.setAssignedTo(TenantContext.actorId());
        }
        audit.record("CARE_TASK_" + status.name(), "CARE_TASK", t.getId(), a.getPatientId(), notes);
        tasks.flush();
        return views(List.of(t)).get(0);
    }

    @Transactional
    public TaskView complete(UUID taskId, String notes) {
        CareTask t = support.requireOrg(tasks.findById(taskId), "Task");
        Admission a = support.requireClinical(t.getAdmissionId());
        if (!ACTIVE.contains(t.getStatus())) {
            throw ApiException.rule("TASK_CLOSED", "This task is already " + t.getStatus().name().toLowerCase() + ".");
        }
        doComplete(t, a, ClinicalSupport.blankToNull(notes));
        audit.record("CARE_TASK_DONE", "CARE_TASK", t.getId(), a.getPatientId(), t.getTitle());
        tasks.flush();
        return views(List.of(t)).get(0);
    }

    void doComplete(CareTask t, Admission a, String notes) {
        t.setStatus(CareTask.Status.DONE);
        t.setCompletedBy(TenantContext.actorId());
        t.setCompletedAt(Instant.now());
        t.setCompletionNotes(notes);
        timeline.record(TimelineService.Entry.builder()
                .patientId(a.getPatientId()).admissionId(a.getId())
                .eventType("CARE_TASK_DONE").category(TimelineEvent.Category.NURSING)
                .title("Task completed: " + t.getTitle()).detail(notes).status("DONE")
                .visibility(TimelineEvent.Visibility.CARE_TEAM).entityType("CARE_TASK").entityId(t.getId()).build());
    }

    /** Closes open tasks created by a workflow source (e.g. collection done or investigation cancelled). */
    void closeForSource(String sourceType, UUID sourceId, Admission a, boolean cancelled, String notes) {
        for (CareTask t : tasks.findBySourceTypeAndSourceIdAndStatusIn(sourceType, sourceId, ACTIVE)) {
            if (cancelled) {
                t.setStatus(CareTask.Status.CANCELLED);
                t.setCompletedBy(TenantContext.actorId());
                t.setCompletedAt(Instant.now());
                t.setCompletionNotes(notes);
            } else {
                doComplete(t, a, notes);
            }
        }
    }

    @Transactional
    public TaskView assign(UUID taskId, UUID userId) {
        CareTask t = support.requireOrg(tasks.findById(taskId), "Task");
        Admission a = support.requireClinical(t.getAdmissionId());
        if (!ACTIVE.contains(t.getStatus())) {
            throw ApiException.rule("TASK_CLOSED", "Closed tasks cannot be reassigned.");
        }
        UUID assignee = userId == null ? null : requireAssignee(userId).getId();
        t.setAssignedTo(assignee);
        if (assignee != null && !assignee.equals(TenantContext.actorId())) {
            notifications.notifyUsers(List.of(assignee), NotificationRequest.builder()
                    .category("TASK").title("Task assigned to you: " + t.getTitle()).message("Admission " + a.getAdmissionNumber())
                    .link("/nurse/tasks").entityType("CARE_TASK").entityId(t.getId())
                    .dedupeKey("TASK_ASSIGN:" + t.getId() + ":" + assignee).build());
        }
        audit.record("CARE_TASK_ASSIGNED", "CARE_TASK", t.getId(), a.getPatientId(), support.userName(assignee));
        tasks.flush();
        return views(List.of(t)).get(0);
    }

    private User requireAssignee(UUID userId) {
        return users.findByIdAndOrganizationId(userId, TenantContext.requireOrganizationId())
                .filter(u -> u.getStatus() == User.Status.ACTIVE && (u.getRole() == Role.NURSE || u.getRole() == Role.DOCTOR))
                .orElseThrow(() -> ApiException.rule("INVALID_ASSIGNEE", "Tasks can only be assigned to active nurses or doctors."));
    }

    public List<TaskView> views(List<CareTask> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, PatientRef> refs = support.refs(list.stream().map(CareTask::getAdmissionId).distinct().toList());
        Map<UUID, String> names = support.userNames(list.stream()
                .flatMap(t -> Stream.of(t.getAssignedTo(), t.getCreatedBy(), t.getCompletedBy())).toList());
        Instant now = Instant.now();
        return list.stream().map(t -> new TaskView(t.getId(), t.getAdmissionId(), refs.get(t.getAdmissionId()), t.getTaskType(),
                t.getTitle(), t.getDescription(), t.getPriority(), t.getStatus(), t.getDueAt(),
                ACTIVE.contains(t.getStatus()) && t.getDueAt() != null && t.getDueAt().isBefore(now),
                t.getAssignedTo(), names.get(t.getAssignedTo()), t.getAssignedRole() == null ? null : t.getAssignedRole().name(),
                t.getSourceType(), t.getSourceId(), names.get(t.getCreatedBy()), t.getCreatedAt(), names.get(t.getCompletedBy()),
                t.getCompletedAt(), t.getCompletionNotes(), t.getVersion())).toList();
    }
}
