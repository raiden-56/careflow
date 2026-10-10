package com.careflow.hms.seed;

/**
 * Module-specific demo data. Contributors run in ascending {@link #order()} after the core seeder
 * (organization, staff, facility, tariffs, patients and admissions) inside one transaction, with the
 * organization set in {@link com.careflow.hms.security.TenantContext}. Use {@link DemoContext#actAs}
 * to create data through real services as a specific demo user.
 *
 * Suggested order ranges: 100 clinical, 200 medication/pharmacy, 300 billing, 400 discharge/follow-up,
 * 500 safety/consent/other.
 */
public interface DemoSeedContributor {

    int order();

    String name();

    void seed(DemoContext ctx);
}
