package com.careflow.hms.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface HandoverRepository extends JpaRepository<Handover, UUID> {

    List<Handover> findTop30ByWardIdInOrderByShiftDateDescCreatedAtDesc(Collection<UUID> wardIds);

    List<Handover> findByOrganizationIdAndStatus(UUID organizationId, Handover.Status status);
}
