package com.careflow.hms.discharge;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DischargeSummaryRepository extends JpaRepository<DischargeSummary, UUID> {

    Optional<DischargeSummary> findByAdmissionId(UUID admissionId);

    List<DischargeSummary> findByAdmissionIdIn(Collection<UUID> admissionIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DischargeSummary s where s.admissionId = :admissionId")
    Optional<DischargeSummary> lockByAdmissionId(@Param("admissionId") UUID admissionId);
}
