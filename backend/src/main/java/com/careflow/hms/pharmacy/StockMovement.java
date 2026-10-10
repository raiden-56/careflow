package com.careflow.hms.pharmacy;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** Append-only stock ledger (enforced by a database trigger). */
@Getter
@Setter
@Entity
@Immutable
@Table(name = "stock_movements")
public class StockMovement extends TenantEntity {

    public enum MovementType { RECEIPT, DISPENSE, RETURN, ADJUSTMENT_IN, ADJUSTMENT_OUT, EXPIRY_WRITE_OFF, DAMAGE }

    @Column(nullable = false)
    private UUID batchId;

    @Column(nullable = false)
    private UUID medicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MovementType movementType;

    /** Signed quantity: positive adds stock, negative removes stock. */
    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int balanceAfter;

    private String referenceType;
    private UUID referenceId;
    private String reason;

    @Column(nullable = false)
    private UUID performedBy;

    @Column(nullable = false)
    private Instant performedAt = Instant.now();
}
