package com.careflow.hms.admission;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Transfer> findByAdmissionIdOrderByRequestedAtDesc(UUID admissionId);

    @Query("select t from Transfer t where t.organizationId = :orgId and t.status in :statuses")
    Page<Transfer> findByStatuses(@Param("orgId") UUID orgId, @Param("statuses") Collection<Transfer.Status> statuses,
                                  Pageable pageable);

    boolean existsByAdmissionIdAndStatusIn(UUID admissionId, Collection<Transfer.Status> statuses);
}
