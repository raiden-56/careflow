package com.careflow.hms.clinical;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CareTaskRepository extends JpaRepository<CareTask, UUID> {

    List<CareTask> findByAdmissionIdOrderByDueAtAsc(UUID admissionId);

    List<CareTask> findByAdmissionIdInAndStatusInOrderByDueAtAsc(Collection<UUID> admissionIds, Collection<CareTask.Status> statuses);

    List<CareTask> findByAdmissionIdAndStatusIn(UUID admissionId, Collection<CareTask.Status> statuses);
}
