package com.careflow.hms.timeline;

import com.careflow.hms.common.PageResponse;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.Role;
import com.careflow.hms.security.TenantContext;
import com.careflow.hms.timeline.TimelineEvent.Category;
import com.careflow.hms.timeline.TimelineEvent.Visibility;
import com.careflow.hms.user.UserRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Shared, chronological admission timeline. Every module records its significant events here;
 * reads are filtered by the viewer's role (see {@link #visibilitiesFor} / {@link #categoriesFor}).
 * Callers MUST perform the object-level access check (AccessPolicy) before reading.
 */
@Service
@RequiredArgsConstructor
public class TimelineService {

    private final TimelineEventRepository repository;
    private final UserRepository users;

    @Builder
    public record Entry(UUID patientId, UUID admissionId, String eventType, Category category, String title,
                        String detail, String status, Visibility visibility, String entityType, UUID entityId,
                        Instant occurredAt) {
    }

    public record TimelineItem(UUID id, UUID admissionId, String eventType, Category category, String title,
                               String detail, String status, Visibility visibility, String entityType,
                               UUID entityId, String actorName, String actorRole, Instant occurredAt) {
    }

    @Transactional
    public TimelineEvent record(Entry entry) {
        TimelineEvent e = new TimelineEvent();
        e.setPatientId(entry.patientId());
        e.setAdmissionId(entry.admissionId());
        e.setEventType(entry.eventType());
        e.setCategory(entry.category());
        e.setTitle(entry.title());
        e.setDetail(truncate(entry.detail(), 2000));
        e.setStatus(entry.status());
        e.setVisibility(entry.visibility() == null ? Visibility.CARE_TEAM : entry.visibility());
        e.setEntityType(entry.entityType());
        e.setEntityId(entry.entityId());
        e.setOccurredAt(entry.occurredAt() == null ? Instant.now() : entry.occurredAt());
        TenantContext.currentUser().ifPresentOrElse(u -> {
            e.setActorUserId(u.userId());
            e.setActorName(u.fullName());
            e.setActorRole(u.role().name());
        }, () -> {
            UUID actor = TenantContext.actorId();
            e.setActorUserId(actor);
            if (actor != null) {
                users.findById(actor).ifPresent(u -> {
                    e.setActorName(u.getFullName());
                    e.setActorRole(u.getRole().name());
                });
            } else {
                e.setActorName("System");
                e.setActorRole("SYSTEM");
            }
        });
        return repository.save(e);
    }

    @Transactional(readOnly = true)
    public PageResponse<TimelineItem> forAdmission(UUID admissionId, Set<Category> filter, int page, int size) {
        CurrentUser u = TenantContext.requireUser();
        if (visibilitiesFor(u.role()).isEmpty()) {
            return new PageResponse<>(java.util.List.of(), page, size, 0, 0);
        }
        Set<Category> cats = intersect(categoriesFor(u.role()), filter);
        return PageResponse.of(repository.findForAdmission(admissionId, visibilitiesFor(u.role()), cats,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                        Sort.by(Sort.Direction.DESC, "occurredAt"))), TimelineService::toItem);
    }

    @Transactional(readOnly = true)
    public PageResponse<TimelineItem> forPatient(UUID patientId, Set<Category> filter, int page, int size) {
        CurrentUser u = TenantContext.requireUser();
        if (visibilitiesFor(u.role()).isEmpty()) {
            return new PageResponse<>(java.util.List.of(), page, size, 0, 0);
        }
        Set<Category> cats = intersect(categoriesFor(u.role()), filter);
        return PageResponse.of(repository.findForPatient(patientId, visibilitiesFor(u.role()), cats,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                        Sort.by(Sort.Direction.DESC, "occurredAt"))), TimelineService::toItem);
    }

    /** Which visibility levels a role may read. */
    public static Set<Visibility> visibilitiesFor(Role role) {
        return switch (role) {
            case PATIENT -> EnumSet.of(Visibility.PATIENT);
            case DOCTOR, NURSE -> EnumSet.of(Visibility.PATIENT, Visibility.CARE_TEAM);
            case BILLING -> EnumSet.of(Visibility.PATIENT, Visibility.BILLING);
            case PHARMACIST -> EnumSet.of(Visibility.PATIENT, Visibility.CARE_TEAM);
            case HOSPITAL_ADMIN -> EnumSet.of(Visibility.PATIENT, Visibility.BILLING, Visibility.INTERNAL);
            case SUPER_ADMIN -> EnumSet.noneOf(Visibility.class);
        };
    }

    /** Which event categories a role may read (limits e.g. administrators to operational events). */
    public static Set<Category> categoriesFor(Role role) {
        return switch (role) {
            case PATIENT, DOCTOR, NURSE -> EnumSet.allOf(Category.class);
            case BILLING -> EnumSet.of(Category.ADMISSION, Category.TRANSFER, Category.BILLING, Category.DISCHARGE);
            case PHARMACIST -> EnumSet.of(Category.ADMISSION, Category.MEDICATION, Category.TRANSFER, Category.DISCHARGE);
            case HOSPITAL_ADMIN -> EnumSet.of(Category.ADMISSION, Category.TRANSFER, Category.BILLING, Category.DISCHARGE, Category.SAFETY);
            case SUPER_ADMIN -> EnumSet.noneOf(Category.class);
        };
    }

    private static Set<Category> intersect(Set<Category> allowed, Set<Category> filter) {
        if (filter == null || filter.isEmpty()) {
            return allowed.isEmpty() ? EnumSet.of(Category.ADMISSION) : allowed;
        }
        EnumSet<Category> result = EnumSet.copyOf(allowed.isEmpty() ? EnumSet.of(Category.ADMISSION) : allowed);
        result.retainAll(filter);
        return result.isEmpty() ? EnumSet.of(Category.ADMISSION) : result;
    }

    private static TimelineItem toItem(TimelineEvent e) {
        return new TimelineItem(e.getId(), e.getAdmissionId(), e.getEventType(), e.getCategory(), e.getTitle(),
                e.getDetail(), e.getStatus(), e.getVisibility(), e.getEntityType(), e.getEntityId(),
                e.getActorName(), e.getActorRole(), e.getOccurredAt());
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
