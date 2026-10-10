package com.careflow.hms.clinical;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LabOrderRepository extends JpaRepository<LabOrder, UUID> {

    List<LabOrder> findByAdmissionIdOrderByOrderedAtDesc(UUID admissionId);

    List<LabOrder> findByAdmissionIdAndStatusIn(UUID admissionId, Collection<LabOrder.Status> statuses);

    List<LabOrder> findByAdmissionIdInAndStatusIn(Collection<UUID> admissionIds, Collection<LabOrder.Status> statuses);

    @Query("select o from LabOrder o where o.organizationId = :orgId and o.status in :statuses")
    Page<LabOrder> findByStatuses(@Param("orgId") UUID orgId, @Param("statuses") Collection<LabOrder.Status> statuses,
                                  Pageable pageable);

    long countByOrganizationIdAndStatusIn(UUID organizationId, Collection<LabOrder.Status> statuses);
}
