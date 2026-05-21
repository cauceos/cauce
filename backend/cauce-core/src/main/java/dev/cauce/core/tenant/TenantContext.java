package dev.cauce.core.tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds the id of the tenant on whose behalf the current thread is operating.
 *
 * <p>Usage: the entry point of a request/operation sets the context, the work runs,
 * and the context is cleared in a {@code finally} block:
 * <pre>{@code
 * TenantContext.setCurrentTenantId(tenantId);
 * try {
 *     // ... transactional work; RlsContextAspect propagates this to the DB session ...
 * } finally {
 *     TenantContext.clear();
 * }
 * }</pre>
 *
 * <p>This is a plain {@link ThreadLocal} (deliberately NOT an
 * {@code InheritableThreadLocal}: inheritance combined with thread pools leaks
 * context across unrelated tasks). It works correctly with both platform and
 * virtual threads — each thread carries its own value. When the project adopts
 * virtual threads broadly and {@code ScopedValue} is finalized (Java 25; it is a
 * preview feature in Java 21), this can migrate to {@code ScopedValue}.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setCurrentTenantId(UUID tenantId) {
        CURRENT_TENANT_ID.set(tenantId);
    }

    public static Optional<UUID> getCurrentTenantId() {
        return Optional.ofNullable(CURRENT_TENANT_ID.get());
    }

    public static void clear() {
        CURRENT_TENANT_ID.remove();
    }
}
