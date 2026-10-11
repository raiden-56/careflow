package com.careflow.hms.billing;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * Source of "now" for financial records. Production code always gets the real time; the demo seeder
 * can run a block "as of" a past instant so that append-only ledger rows are inserted with their
 * historical timestamps (they can never be backdated afterwards).
 */
public final class BillingClock {

    private static final ThreadLocal<Instant> OVERRIDE = new ThreadLocal<>();

    private BillingClock() {
    }

    public static Instant now() {
        Instant o = OVERRIDE.get();
        return o != null ? o : Instant.now();
    }

    /** Runs work with {@link #now()} fixed to the given instant (seeding / historical imports only). */
    public static <T> T at(Instant instant, Supplier<T> work) {
        Instant previous = OVERRIDE.get();
        OVERRIDE.set(instant);
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

    public static void at(Instant instant, Runnable work) {
        at(instant, () -> {
            work.run();
            return null;
        });
    }
}
