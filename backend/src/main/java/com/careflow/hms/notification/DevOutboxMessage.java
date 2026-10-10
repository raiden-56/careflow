package com.careflow.hms.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A message "sent" by the development email/SMS adapter. No external provider is contacted. */
@Getter
@Setter
@Entity
@Table(name = "dev_outbox")
public class DevOutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String destination;

    private String subject;

    @Column(nullable = false)
    private String body;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
