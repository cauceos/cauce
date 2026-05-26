package dev.cauce.core.tenant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a transactional method (or type) that must run WITHOUT a tenant context,
 * so the RLS context aspect skips it.
 *
 * <p>Reserved for two legitimate cases:
 * <ul>
 *   <li><b>Bootstrap</b>: operations that cannot have a tenant context yet — chiefly
 *       creating the very first operator.</li>
 *   <li><b>System processes</b>: workers that operate across the whole queue and have no
 *       single owning tenant (e.g. the asynchronous invocation worker and its reaper).
 *       Per-row processing still establishes a tenant context downstream before any
 *       tenant-scoped service is invoked.</li>
 * </ul>
 *
 * <p>Both cases rely on the connection bypassing RLS (a privileged/superuser connection
 * today). When the application is later wired to the least-privilege {@code cauce_app}
 * role, this annotation will still be the in-process marker — what changes is the
 * DB-level mechanism (a worker-only role with {@code BYPASSRLS}, or {@code SECURITY
 * DEFINER} functions). Do not use this to opt out of tenant isolation for ordinary
 * operations.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface NoTenantContext {
}
