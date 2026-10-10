package com.careflow.hms.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {

    Optional<NotificationTemplate> findByOrganizationIdAndCodeAndChannelAndActiveTrue(UUID organizationId, String code, String channel);

    List<NotificationTemplate> findByOrganizationIdOrderByCode(UUID organizationId);
}
