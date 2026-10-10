package com.careflow.hms.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Professional profile of a staff user; shares its primary key with {@link User}. */
@Getter
@Setter
@Entity
@Table(name = "staff_profiles")
public class StaffProfile {

    @Id
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private UUID organizationId;

    private String employeeCode;
    private String designation;
    private UUID departmentId;
    private String specialty;
    private String registrationNumber;
    private String qualification;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;
}
