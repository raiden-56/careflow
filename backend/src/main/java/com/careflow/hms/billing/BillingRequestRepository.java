package com.careflow.hms.billing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BillingRequestRepository extends JpaRepository<BillingRequest, UUID> {

    List<BillingRequest> findByInvoiceIdOrderByRaisedAtDesc(UUID invoiceId);

    List<BillingRequest> findByPatientIdOrderByRaisedAtDesc(UUID patientId);

    Page<BillingRequest> findByOrganizationId(UUID organizationId, Pageable pageable);
}
