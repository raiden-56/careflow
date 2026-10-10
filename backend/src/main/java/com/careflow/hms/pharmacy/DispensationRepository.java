package com.careflow.hms.pharmacy;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DispensationRepository extends JpaRepository<Dispensation, UUID> {

    List<Dispensation> findByOrderIdOrderByDispensedAtDesc(UUID orderId);

    List<Dispensation> findByOrderIdIn(Collection<UUID> orderIds);

    Page<Dispensation> findByOrganizationId(UUID organizationId, Pageable pageable);

    List<Dispensation> findByAdmissionIdOrderByDispensedAtDesc(UUID admissionId);
}
