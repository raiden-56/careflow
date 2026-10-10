package com.careflow.hms.facility;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BedStatusHistoryRepository extends JpaRepository<BedStatusHistory, UUID> {

    List<BedStatusHistory> findTop50ByBedIdOrderByChangedAtDesc(UUID bedId);
}
