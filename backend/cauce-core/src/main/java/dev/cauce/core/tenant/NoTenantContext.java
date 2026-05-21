package dev.cauce.core.tenant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a transactional method (or type) that must run WITHOUT a tenant context,
 * so the RLS context aspect skips it.
 *
 * <p>Use only for genuine bootstrap operations that cannot have a tenant context
 * yet — chiefly creating the very first operator. Such operations rely on the
 * connection bypassing RLS (a privileged/superuser connection today). Do not use
 * this to opt out of tenant isolation for ordinary operations.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface NoTenantContext {
}
