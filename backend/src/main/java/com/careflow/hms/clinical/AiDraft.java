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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An automatically generated DRAFT (summary/handover). Never part of the official record until an
 * authorized clinician reviews and accepts it. Every statement references its source records.
 */
@Getter
@Setter
@Entity
@Table(name = "ai_drafts")
public class AiDraft extends TenantEntity {

    public enum DraftType { PROGRESS_SUMMARY, HANDOVER, DISCHARGE_SUMMARY, MISSING_INFO }

    public enum Status { DRAFT, ACCEPTED, REJECTED }

    @Column(nullable = false)
    private UUID admissionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DraftType draftType;

    @Column(nullable = false)
    private String generator;

    @Column(nullable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Map<String, Object>> sourceRefs = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private UUID reviewedBy;
    private Instant reviewedAt;
    private UUID publishedNoteId;

    @Version
    private Long version;
}
