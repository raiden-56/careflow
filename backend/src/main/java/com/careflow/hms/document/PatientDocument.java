package com.careflow.hms.document;

import com.careflow.hms.common.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "documents")
public class PatientDocument extends TenantEntity {

    public enum DocumentType { LAB_REPORT, IMAGING, DISCHARGE_SUMMARY, CONSENT_FORM, ID_PROOF, INSURANCE, REFERRAL, OTHER }

    public enum Status { ACTIVE, ARCHIVED }

    @Column(nullable = false)
    private UUID patientId;

    private UUID admissionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentType documentType;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String sha256;

    /** Opaque key under the configured upload directory; never derived from user input. */
    @Column(nullable = false)
    private String storageKey;

    @Column(nullable = false)
    private boolean releasedToPatient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(nullable = false)
    private UUID uploadedBy;

    @Column(nullable = false)
    private Instant uploadedAt = Instant.now();

    @Version
    private Long version;
}
