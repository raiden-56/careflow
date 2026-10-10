package com.careflow.hms.notification;

import lombok.Builder;

import java.util.Set;
import java.util.UUID;

/**
 * A notification to deliver.
 *
 * @param dedupeKey     stable key for the underlying event; a recipient never receives two
 *                      notifications with the same key (e.g. "MED_OVERDUE:{administrationId}")
 * @param channels      IN_APP is always used; EMAIL/SMS go through the configured gateway
 */
@Builder
public record NotificationRequest(
        String category,
        Notification.Priority priority,
        String title,
        String message,
        String link,
        String entityType,
        UUID entityId,
        String dedupeKey,
        boolean requiresAck,
        Set<NotificationDelivery.Channel> channels) {
}
