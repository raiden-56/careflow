package com.careflow.hms.notification;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Editable message template. Placeholders use {{name}} syntax. */
@Getter
@Setter
@Entity
@Table(name = "notification_templates")
public class NotificationTemplate extends TenantEntity {

    @Column(nullable = false)
    private String code;

    /** IN_APP, EMAIL or SMS. */
    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String body;

    @Column(nullable = false)
    private boolean active = true;

    private UUID updatedBy;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private Long version;
}
