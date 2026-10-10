package com.careflow.hms.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StaffProfileRepository extends JpaRepository<StaffProfile, UUID> {

    List<StaffProfile> findByUserIdIn(Collection<UUID> userIds);

    List<StaffProfile> findByOrganizationIdAndDepartmentId(UUID organizationId, UUID departmentId);
}
