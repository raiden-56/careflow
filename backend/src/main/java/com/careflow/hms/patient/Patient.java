package com.careflow.hms.patient;

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
import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

/** Patient identity. Kept separate from admissions so a patient can have many admissions over time. */
@Getter
@Setter
@Entity
@Table(name = "patients")
public class Patient extends TenantEntity {

    public enum Gender { MALE, FEMALE, OTHER, UNDISCLOSED }

    public enum Status { ACTIVE, ARCHIVED, DECEASED }

    @Column(nullable = false, updatable = false)
    private String mrn;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false)
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gender gender;

    private String bloodGroup;
    private String phone;
    private String email;
    private String addressLine;
    private String city;
    private String state;
    private String pincode;
    private String emergencyContactName;
    private String emergencyContactPhone;
    private String emergencyContactRelation;
    private String abhaNumber;
    private String preferredLanguage;

    /** Portal login linked to this patient (optional). */
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    private UUID createdBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    public String fullName() {
        return firstName + " " + lastName;
    }

    public int ageYears(LocalDate today) {
        return Period.between(dateOfBirth, today).getYears();
    }
}
