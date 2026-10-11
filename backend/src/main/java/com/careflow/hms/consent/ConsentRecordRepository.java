package com.careflow.hms.consent;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {

    List<ConsentRecord> findByPatientIdOrderByGrantedAtDesc(UUID patientId);

    Optional<ConsentRecord> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("""
            select c from ConsentRecord c
            where c.organizationId = :orgId
              and (:patientId is null or c.patientId = :patientId)
              and (:type is null or c.consentType = :type)
              and (:mode = 'ALL'
                   or (:mode = 'GRANTED' and c.status = 'GRANTED' and (c.expiresAt is null or c.expiresAt > :now))
                   or (:mode = 'EXPIRED' and (c.status = 'EXPIRED' or (c.status = 'GRANTED' and c.expiresAt <= :now)))
                   or (:mode = 'WITHDRAWN' and c.status = 'WITHDRAWN')
                   or (:mode = 'DECLINED' and c.status = 'DECLINED'))
            """)
    Page<ConsentRecord> search(@Param("orgId") UUID orgId, @Param("patientId") UUID patientId,
                               @Param("mode") String mode, @Param("now") java.time.Instant now,
                               @Param("type") ConsentRecord.ConsentType type, Pageable pageable);
}
