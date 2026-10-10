package com.careflow.hms.clinical;

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

/**
 * Clinical / nursing documentation. Notes are never edited in place: corrections create an
 * amendment (amendsNoteId) and the original is marked AMENDED or ENTERED_IN_ERROR.
 */
@Getter
@Setter
@Entity
@Table(name = "clinical_notes")
public class ClinicalNote extends TenantEntity {

    public enum NoteType {
        ADMISSION_ASSESSMENT, PROGRESS, DOCTOR_REVIEW, NURSING_ASSESSMENT, NURSING_OBSERVATION,
        PROCEDURE, CONSULTATION, EDUCATION;

        public boolean isNursing() {
            return this == NURSING_ASSESSMENT || this == NURSING_OBSERVATION || this == EDUCATION;
        }
    }

    public enum Status { FINAL, AMENDED, ENTERED_IN_ERROR }

    @Column(nullable = false)
    private UUID admissionId;

    @Column(nullable = false)
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NoteType noteType;

    @Column(nullable = false)
    private String title;

    private String subjective;
    private String objective;
    private String assessment;
    private String plan;

    @Column(nullable = false)
    private String content;

    /** Plain-language version suitable for the patient portal (only shown once released). */
    private String patientSummary;

    @Column(nullable = false)
    private boolean releasedToPatient;

    private UUID releasedBy;
    private Instant releasedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.FINAL;

    private UUID amendsNoteId;
    private String correctionReason;

    @Column(nullable = false)
    private UUID authorId;

    @Column(nullable = false)
    private Instant authoredAt = Instant.now();

    @Version
    private Long version;
}
