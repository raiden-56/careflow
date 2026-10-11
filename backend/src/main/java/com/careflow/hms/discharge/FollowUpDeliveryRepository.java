package com.careflow.hms.discharge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FollowUpDeliveryRepository extends JpaRepository<FollowUpDelivery, UUID> {

    List<FollowUpDelivery> findByFollowUpIdOrderByAttemptedAtDesc(UUID followUpId);

    List<FollowUpDelivery> findByFollowUpIdInOrderByAttemptedAtDesc(Collection<UUID> followUpIds);

    boolean existsByFollowUpIdAndDedupeKey(UUID followUpId, String dedupeKey);
}
