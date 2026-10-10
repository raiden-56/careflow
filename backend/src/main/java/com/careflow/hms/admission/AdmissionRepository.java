package com.careflow.hms.admission;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdmissionRepository extends JpaRepository<Admission, UUID> {

    Optional<Admission> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Admission a where a.id = :id and a.organizationId = :orgId")
    Optional<Admission> lockById(@Param("id") UUID id, @Param("orgId") UUID orgId);

    List<Admission> findByPatientIdOrderByAdmittedAtDesc(UUID patientId);

    List<Admission> findByIdIn(Collection<UUID> ids);

    @Query("select a from Admission a where a.patientId = :patientId and a.status in ('PENDING_BED','ADMITTED','DISCHARGE_IN_PROGRESS')")
    Optional<Admission> findOpenByPatient(@Param("patientId") UUID patientId);

    @Query("""
            select a from Admission a, Patient p
            where p.id = a.patientId
              and a.organizationId = :orgId
              and a.status in :statuses
              and (:departmentId is null or a.departmentId = :departmentId)
              and (:doctorId is null or a.attendingDoctorId = :doctorId)
              and (:q is null
                   or lower(concat(p.firstName, ' ', p.lastName)) like :q
                   or lower(p.mrn) like :q
                   or lower(a.admissionNumber) like :q)
            """)
    Page<Admission> search(@Param("orgId") UUID orgId,
                           @Param("statuses") Collection<Admission.Status> statuses,
                           @Param("departmentId") UUID departmentId,
                           @Param("doctorId") UUID doctorId,
                           @Param("q") String q,
                           Pageable pageable);

    /** Open admissions where the user is the attending doctor or an active care-team member. */
    @Query("""
            select distinct a from Admission a
            where a.organizationId = :orgId
              and a.status in ('PENDING_BED','ADMITTED','DISCHARGE_IN_PROGRESS')
              and (a.attendingDoctorId = :userId
                   or exists (select 1 from AdmissionCareTeam t
                              where t.admissionId = a.id and t.userId = :userId and t.removedAt is null))
            order by a.admittedAt desc
            """)
    List<Admission> findOpenForClinician(@Param("orgId") UUID orgId, @Param("userId") UUID userId);

    /** Open admissions currently located (active bed assignment) in any of the given wards. */
    @Query("""
            select a from Admission a, BedAssignment ba, Bed b
            where ba.admissionId = a.id and ba.releasedAt is null and b.id = ba.bedId
              and a.organizationId = :orgId
              and b.wardId in :wardIds
              and a.status in ('ADMITTED','DISCHARGE_IN_PROGRESS')
            order by a.admittedAt desc
            """)
    List<Admission> findOpenInWards(@Param("orgId") UUID orgId, @Param("wardIds") Collection<UUID> wardIds);

    long countByOrganizationIdAndStatus(UUID organizationId, Admission.Status status);

    long countByOrganizationIdAndAdmittedAtBetween(UUID organizationId, Instant from, Instant to);

    long countByOrganizationIdAndDischargedAtBetween(UUID organizationId, Instant from, Instant to);
}
