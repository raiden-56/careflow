package com.careflow.hms.facility;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {

    Optional<Department> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Department> findByOrganizationIdOrderByName(UUID organizationId);

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);
}
