package dev.cauce.memory.tenant;

import dev.cauce.core.tenant.MissingTenantContextException;
import dev.cauce.core.tenant.NoTenantContext;
import dev.cauce.core.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.Method;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Propagates the {@link TenantContext} into the PostgreSQL session so Row-Level
 * Security policies apply, by running {@code set_config('app.current_tenant_id', …, true)}
 * (transaction-scoped) on the transaction's EntityManager.
 *
 * <p>Runs INSIDE the active transaction: the aspect is ordered
 * {@link Ordered#LOWEST_PRECEDENCE} while the transaction advisor is one step more
 * outer (see {@code TenantRlsConfiguration}), so by the time this advice executes a
 * transaction is open and the EntityManager is bound to it.
 *
 * <p>Scoped to {@code @Transactional} methods of {@code @Service} beans, so it does
 * not fire on Spring Data repository internals. Methods (or types) annotated
 * {@link NoTenantContext} are skipped (bootstrap).
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class RlsContextAspect {

    private static final Logger log = LoggerFactory.getLogger(RlsContextAspect.class);

    @PersistenceContext
    private EntityManager entityManager;

    @Around("@within(org.springframework.stereotype.Service) && "
            + "@annotation(org.springframework.transaction.annotation.Transactional)")
    public Object applyTenantContext(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        if (isExempt(method)) {
            return joinPoint.proceed();
        }

        UUID tenantId = TenantContext.getCurrentTenantId().orElseThrow(() ->
                new MissingTenantContextException("No tenant context set for transactional method "
                        + method.getDeclaringClass().getSimpleName() + "." + method.getName()));

        entityManager.createNativeQuery("SELECT set_config('app.current_tenant_id', ?1, true)")
                .setParameter(1, tenantId.toString())
                .getSingleResult();
        log.debug("RLS tenant context set to {}", tenantId);

        return joinPoint.proceed();
    }

    private static boolean isExempt(Method method) {
        return method.isAnnotationPresent(NoTenantContext.class)
                || method.getDeclaringClass().isAnnotationPresent(NoTenantContext.class);
    }
}
