package com.careflow.hms.common;

import com.careflow.hms.security.CurrentUser;
import com.careflow.hms.security.TenantContext;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Timestamps are stored in UTC; hospital-local dates use the organization's configured timezone. */
public final class TimeUtil {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");

    private TimeUtil() {
    }

    /** The current user's hospital timezone (falls back to Asia/Kolkata for background jobs). */
    public static ZoneId zone() {
        return TenantContext.currentUser().map(CurrentUser::zone).orElse(DEFAULT_ZONE);
    }

    public static LocalDate today() {
        return LocalDate.now(zone());
    }

    public static LocalDate localDate(Instant instant) {
        return instant.atZone(zone()).toLocalDate();
    }

    public static LocalDate localDate(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toLocalDate();
    }

    public static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(zone()).toInstant();
    }

    public static Instant startOfDay(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant();
    }
}
