package com.careflow.hms.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DevOutboxMessageRepository extends JpaRepository<DevOutboxMessage, UUID> {

    List<DevOutboxMessage> findTop50ByOrderByCreatedAtDesc();
}
