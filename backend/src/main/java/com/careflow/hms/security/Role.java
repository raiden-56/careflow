package com.careflow.hms.security;

public enum Role {
    SUPER_ADMIN,
    HOSPITAL_ADMIN,
    DOCTOR,
    NURSE,
    BILLING,
    PHARMACIST,
    PATIENT;

    public boolean isClinical() {
        return this == DOCTOR || this == NURSE;
    }

    public boolean isStaff() {
        return this != PATIENT && this != SUPER_ADMIN;
    }
}
