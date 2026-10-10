package com.careflow.hms.security;

import com.careflow.hms.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Resolves the organization (tenant) for the current unit of work. Normally derived from the
 * authenticated user; background jobs and seeders run explicit blocks with {@link #runAs}.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> OVERRIDE = new ThreadLocal<>();
    private static final ThreadLocal<UUID> SYSTEM_ACTOR = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<CurrentUser> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser cu) {
            return Optional.of(cu);
        }
        return Optional.empty();
    }

    /** The authenticated user; throws 401 when there is none. */
    public static CurrentUser requireUser() {
        return currentUser().orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required"));
    }

    public static UUID requireOrganizationId() {
        UUID o = OVERRIDE.get();
        if (o != null) {
            return o;
        }
        return currentUser().map(CurrentUser::organizationId).orElseThrow(() ->
                new IllegalStateException("No organization in context"));
    }

    /**
     * The acting user id: the authenticated user, or the system actor configured for a
     * background job via {@link #runAs(UUID, UUID, Callable)}.
     */
    public static UUID actorId() {
        return currentUser().map(CurrentUser::userId).orElseGet(SYSTEM_ACTOR::get);
    }

    public static <T> T runAs(UUID organizationId, UUID systemActorId, Callable<T> work) {
        UUID prevOrg = OVERRIDE.get();
        UUID prevActor = SYSTEM_ACTOR.get();
        OVERRIDE.set(organizationId);
        SYSTEM_ACTOR.set(systemActorId);
        try {
            return work.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            restore(OVERRIDE, prevOrg);
            restore(SYSTEM_ACTOR, prevActor);
        }
    }

    public static void runAs(UUID organizationId, UUID systemActorId, Runnable work) {
        runAs(organizationId, systemActorId, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T withOrganization(UUID organizationId, Supplier<T> work) {
        return runAs(organizationId, actorId(), work::get);
    }

    private static <T> void restore(ThreadLocal<T> tl, T prev) {
        if (prev == null) {
            tl.remove();
        } else {
            tl.set(prev);
        }
    }
}
