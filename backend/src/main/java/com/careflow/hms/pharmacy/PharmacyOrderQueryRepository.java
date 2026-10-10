package com.careflow.hms.pharmacy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyOrderQueryRepository extends JpaRepository<PharmacyOrderQuery, UUID> {

    Optional<PharmacyOrderQuery> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<PharmacyOrderQuery> findByOrderIdAndStatus(UUID orderId, PharmacyOrderQuery.Status status);

    List<PharmacyOrderQuery> findByOrderIdInAndStatus(Collection<UUID> orderIds, PharmacyOrderQuery.Status status);

    List<PharmacyOrderQuery> findByOrderIdOrderByRaisedAtDesc(UUID orderId);
}
