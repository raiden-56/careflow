package com.careflow.hms.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByInvoiceIdOrderByCreatedAtAsc(UUID invoiceId);

    @Query("select coalesce(sum(e.amount), 0) from LedgerEntry e where e.invoiceId = :invoiceId")
    BigDecimal balance(@Param("invoiceId") UUID invoiceId);

    @Query("select coalesce(sum(e.amount), 0) from LedgerEntry e where e.invoiceId = :invoiceId and e.entryType in :types")
    BigDecimal sumByTypes(@Param("invoiceId") UUID invoiceId, @Param("types") Collection<LedgerEntry.EntryType> types);

    @Query("""
            select coalesce(sum(e.amount), 0) from LedgerEntry e
            where e.organizationId = :orgId and e.entryType in :types and e.createdAt >= :from and e.createdAt < :to
            """)
    BigDecimal sumByTypesInRange(@Param("orgId") UUID orgId, @Param("types") Collection<LedgerEntry.EntryType> types,
                                 @Param("from") Instant from, @Param("to") Instant to);

    @Query("select e from LedgerEntry e where e.organizationId = :orgId and e.createdAt >= :from and e.createdAt < :to order by e.createdAt")
    List<LedgerEntry> findInRange(@Param("orgId") UUID orgId, @Param("from") Instant from, @Param("to") Instant to);
}
