package com.careflow.hms.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface InvoiceLineItemRepository extends JpaRepository<InvoiceLineItem, UUID> {

    List<InvoiceLineItem> findByInvoiceIdOrderByServiceDateAscPostedAtAsc(UUID invoiceId);

    boolean existsByInvoiceIdAndSourceTypeAndSourceIdAndServiceDateAndStatus(UUID invoiceId,
                                                                             InvoiceLineItem.SourceType sourceType,
                                                                             UUID sourceId, LocalDate serviceDate,
                                                                             InvoiceLineItem.Status status);
}
