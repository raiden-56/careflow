package com.careflow.hms.facility;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "beds")
public class Bed extends TenantEntity {

    public enum BedType { STANDARD, ICU, ISOLATION, PAEDIATRIC, BARIATRIC, MATERNITY }

    public enum Status {
        AVAILABLE, OCCUPIED, RESERVED, CLEANING, MAINTENANCE;

        /**
         * Manual (housekeeping) transitions. OCCUPIED is only entered/left through
         * bed assignment and release, never by a direct status change.
         */
        private static final Map<Status, Set<Status>> MANUAL = Map.of(
                AVAILABLE, EnumSet.of(RESERVED, CLEANING, MAINTENANCE),
                RESERVED, EnumSet.of(AVAILABLE, CLEANING, MAINTENANCE),
                CLEANING, EnumSet.of(AVAILABLE, MAINTENANCE),
                MAINTENANCE, EnumSet.of(AVAILABLE, CLEANING),
                OCCUPIED, EnumSet.noneOf(Status.class));

        public boolean canManuallyMoveTo(Status target) {
            return MANUAL.get(this).contains(target);
        }
    }

    @Column(nullable = false)
    private UUID wardId;

    @Column(nullable = false)
    private UUID roomId;

    @Column(nullable = false)
    private String bedNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BedType bedType = BedType.STANDARD;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.AVAILABLE;

    @Column(nullable = false)
    private boolean isolationCapable;

    @Column(nullable = false)
    private boolean oxygenSupported;

    private String notes;

    @Column(nullable = false)
    private Instant statusChangedAt = Instant.now();

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;
}
