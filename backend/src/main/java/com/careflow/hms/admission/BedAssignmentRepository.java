package com.careflow.hms.admission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BedAssignmentRepository extends JpaRepository<BedAssignment, UUID> {

    Optional<BedAssignment> findByAdmissionIdAndReleasedAtIsNull(UUID admissionId);

    Optional<BedAssignment> findByBedIdAndReleasedAtIsNull(UUID bedId);

    List<BedAssignment> findByAdmissionIdOrderByAssignedAtAsc(UUID admissionId);

    List<BedAssignment> findByAdmissionIdInAndReleasedAtIsNull(Collection<UUID> admissionIds);

    List<BedAssignment> findByBedIdInAndReleasedAtIsNull(Collection<UUID> bedIds);

    List<BedAssignment> findTop20ByBedIdOrderByAssignedAtDesc(UUID bedId);
}
