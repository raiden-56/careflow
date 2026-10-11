package com.careflow.hms.facility;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BedRepository extends JpaRepository<Bed, UUID> {

    Optional<Bed> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Row-locks the bed for the duration of the transaction (bed allocation concurrency control). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Bed b where b.id = :id and b.organizationId = :orgId")
    Optional<Bed> lockById(@Param("id") UUID id, @Param("orgId") UUID orgId);

    List<Bed> findByOrganizationIdAndActiveTrue(UUID organizationId);

    List<Bed> findByOrganizationIdAndWardIdAndActiveTrue(UUID organizationId, UUID wardId);

    List<Bed> findByOrganizationIdAndWardId(UUID organizationId, UUID wardId);

    List<Bed> findByIdIn(Collection<UUID> ids);

    long countByOrganizationIdAndActiveTrueAndStatus(UUID organizationId, Bed.Status status);
}
