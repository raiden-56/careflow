package com.careflow.hms.notification;

import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.PageResponse;
import com.careflow.hms.config.CareFlowProperties;
import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.TenantContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The caller's own notifications. Every query is scoped to the authenticated recipient. */
@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final DevOutboxMessageRepository outbox;
    private final SseHub sseHub;
    private final CareFlowProperties props;

    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<NotificationService.NotificationView> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                                                   @RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "20") int size) {
        CurrentUser u = TenantContext.requireUser();
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = unreadOnly
                ? notifications.findByRecipientUserIdAndReadAtIsNull(u.userId(), pageable)
                : notifications.findByRecipientUserId(u.userId(), pageable);
        return PageResponse.of(result, NotificationService.NotificationView::of);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", notifications.countByRecipientUserIdAndReadAtIsNull(TenantContext.requireUser().userId()));
    }

    @PostMapping("/{id}/read")
    @Transactional
    public NotificationService.NotificationView markRead(@PathVariable UUID id) {
        Notification n = own(id);
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now());
        }
        return NotificationService.NotificationView.of(n);
    }

    @PostMapping("/read-all")
    @Transactional
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", notifications.markAllRead(TenantContext.requireUser().userId(), Instant.now()));
    }

    @PostMapping("/{id}/acknowledge")
    @Transactional
    public NotificationService.NotificationView acknowledge(@PathVariable UUID id) {
        Notification n = own(id);
        Instant now = Instant.now();
        if (n.getReadAt() == null) {
            n.setReadAt(now);
        }
        if (n.getAcknowledgedAt() == null) {
            n.setAcknowledgedAt(now);
        }
        return NotificationService.NotificationView.of(n);
    }

    @GetMapping("/{id}/deliveries")
    @Transactional(readOnly = true)
    public List<NotificationDelivery> deliveryStatus(@PathVariable UUID id) {
        own(id);
        return deliveries.findByNotificationId(id);
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return sseHub.register(TenantContext.requireUser().userId());
    }

    /** DEVELOPMENT ONLY: messages captured by the dev email/SMS adapter. Disabled unless configured. */
    @GetMapping("/dev-outbox")
    @Transactional(readOnly = true)
    public List<DevOutboxMessage> devOutbox() {
        if (!props.dev().outboxEnabled()) {
            throw ApiException.notFound("Endpoint");
        }
        CurrentUser u = TenantContext.requireUser();
        return outbox.findTop50ByOrderByCreatedAtDesc().stream()
                .filter(m -> u.organizationId().equals(m.getOrganizationId()) || m.getOrganizationId() == null)
                .toList();
    }

    private Notification own(UUID id) {
        return notifications.findByIdAndRecipientUserId(id, TenantContext.requireUser().userId())
                .orElseThrow(() -> ApiException.notFound("Notification"));
    }
}
