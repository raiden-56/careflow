package com.careflow.hms.clinical;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "handover_items")
public class HandoverItem {

    public enum Priority { NORMAL, WATCH, CRITICAL }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID handoverId;

    @Column(nullable = false)
    private UUID admissionId;

    @Column(nullable = false)
    private UUID patientId;

    private String conditionSummary;
    private String importantObservations;
    private String pendingTasks;
    private String outstandingItems;
    private String escalations;
    private String pendingMedications;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority = Priority.NORMAL;
}
