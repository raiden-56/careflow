package com.careflow.hms.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * DEVELOPMENT ADAPTER: records email/SMS messages in the dev_outbox table instead of sending them.
 * Clearly labelled as such in delivery records (provider = "dev-outbox").
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DevOutboxGateway implements ExternalChannelGateway {

    public static final String PROVIDER = "dev-outbox";

    private final DevOutboxMessageRepository outbox;

    @Override
    public Result send(UUID organizationId, NotificationDelivery.Channel channel, String destination,
                       String subject, String body) {
        if (destination == null || destination.isBlank()) {
            return new Result(NotificationDelivery.Status.SKIPPED, PROVIDER, "No destination on file");
        }
        DevOutboxMessage m = new DevOutboxMessage();
        m.setOrganizationId(organizationId);
        m.setChannel(channel.name());
        m.setDestination(destination);
        m.setSubject(subject);
        m.setBody(body);
        outbox.save(m);
        log.info("[DEV OUTBOX] {} to {}: {}", channel, mask(destination), subject);
        return new Result(NotificationDelivery.Status.SENT, PROVIDER, null);
    }

    private static String mask(String d) {
        return d.length() <= 4 ? "****" : d.substring(0, 2) + "****" + d.substring(d.length() - 2);
    }
}
