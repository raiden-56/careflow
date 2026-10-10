package com.careflow.hms.pharmacy;

import com.careflow.hms.common.TimeUtil;

import java.time.Instant;
import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * Time source for pharmacy events. In normal operation this is simply "now". The demo seeder uses
 * {@link #runAt} to create realistic history (receipts and dispensations on earlier days) through the
 * real services; stock movements are append-only and cannot be backdated afterwards.
 */
public final class PharmacyClock {

    private static final ThreadLocal<Instant> OVERRIDE = new ThreadLocal<>();

    private PharmacyClock() {
    }

    public static Instant now() {
        Instant o = OVERRIDE.get();
        return o != null ? o : Instant.now();
    }

    /** Hospital-local calendar date of {@link #now()}. */
    public static LocalDate today() {
        return TimeUtil.localDate(now());
    }

    /** True while running backdated seed work (live notifications are skipped for historical events). */
    public static boolean isBackdated() {
        return OVERRIDE.get() != null;
    }

    public static <T> T runAt(Instant at, Supplier<T> work) {
        Instant previous = OVERRIDE.get();
        OVERRIDE.set(at);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                OVERRIDE.remove();
            } else {
                OVERRIDE.set(previous);
            }
        }
    }
}
