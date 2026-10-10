package com.careflow.hms.facility;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StaffWardAssignmentRepository extends JpaRepository<StaffWardAssignment, UUID> {

    List<StaffWardAssignment> findByUserIdAndActiveTrue(UUID userId);

    List<StaffWardAssignment> findByWardIdAndActiveTrue(UUID wardId);

    @Query("select a.wardId from StaffWardAssignment a where a.userId = :userId and a.active = true")
    List<UUID> activeWardIds(@Param("userId") UUID userId);

    @Query("select a.userId from StaffWardAssignment a where a.wardId = :wardId and a.active = true")
    List<UUID> activeUserIdsForWard(@Param("wardId") UUID wardId);
}
