package com.careflow.hms.notification;

/**
 * Integration boundary for external notification providers (email / SMS / push).
 * Production deployments supply a provider-backed implementation; development uses
 * {@link DevOutboxGateway}, which never contacts an external service.
 */
public interface ExternalChannelGateway {

    record Result(NotificationDelivery.Status status, String provider, String error) {
    }

    Result send(java.util.UUID organizationId, NotificationDelivery.Channel channel, String destination,
                String subject, String body);
}
