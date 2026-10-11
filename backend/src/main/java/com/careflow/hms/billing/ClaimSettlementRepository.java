package com.careflow.hms.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ClaimSettlementRepository extends JpaRepository<ClaimSettlement, UUID> {

    List<ClaimSettlement> findByClaimIdOrderBySettledAtAsc(UUID claimId);

    List<ClaimSettlement> findByClaimIdInOrderBySettledAtAsc(Collection<UUID> claimIds);

    boolean existsByClaimIdAndSettlementReferenceIgnoreCase(UUID claimId, String settlementReference);
}
