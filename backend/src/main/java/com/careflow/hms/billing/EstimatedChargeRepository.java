package com.careflow.hms.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EstimatedChargeRepository extends JpaRepository<EstimatedCharge, UUID> {

    List<EstimatedCharge> findByInvoiceIdAndActiveTrueOrderByCreatedAtAsc(UUID invoiceId);

    List<EstimatedCharge> findByInvoiceIdOrderByCreatedAtAsc(UUID invoiceId);
}
