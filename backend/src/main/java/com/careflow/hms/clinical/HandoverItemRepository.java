package com.careflow.hms.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HandoverItemRepository extends JpaRepository<HandoverItem, UUID> {

    List<HandoverItem> findByHandoverId(UUID handoverId);
}
