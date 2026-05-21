package dev.cauce.memory.tenant;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Makes the transaction advisor run one step OUTER to {@link RlsContextAspect}
 * (which is {@link Ordered#LOWEST_PRECEDENCE}), guaranteeing that the aspect runs
 * inside an already-open transaction so {@code set_config(..., is_local = true)}
 * lands on the transaction's connection.
 *
 * <p>Declaring {@code @EnableTransactionManagement} here also makes Spring Boot's
 * default transaction auto-configuration back off, so this ordering is the single
 * source of truth wherever {@code dev.cauce.memory} is component-scanned.
 */
@Configuration
@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 1)
public class TenantRlsConfiguration {
}
