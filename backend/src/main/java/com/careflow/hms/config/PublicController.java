package com.careflow.hms.config;

import com.careflow.hms.common.ApiException;
import com.careflow.hms.notification.DevOutboxMessage;
import com.careflow.hms.notification.DevOutboxMessageRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "Public")
@RestController
@RequiredArgsConstructor
public class PublicController {

    private final CareFlowProperties props;
    private final DevOutboxMessageRepository outbox;

    @GetMapping("/api/public/config")
    public Map<String, Object> config() {
        return Map.of(
                "appName", "CareFlow HMS",
                "demoMode", props.demo().enabled(),
                "devOutboxEnabled", props.dev().outboxEnabled());
    }

    /**
     * DEVELOPMENT ONLY - shows the messages captured by the development email/SMS adapter so the
     * password-reset and follow-up notification flows can be demonstrated without a mail provider.
     * Returns 404 unless careflow.dev.outbox-enabled=true. Never enable this in production.
     */
    @GetMapping("/api/dev/outbox")
    @Transactional(readOnly = true)
    public List<DevOutboxMessage> devOutbox() {
        if (!props.dev().outboxEnabled()) {
            throw ApiException.notFound("Endpoint");
        }
        return outbox.findTop50ByOrderByCreatedAtDesc();
    }
}
