package dev.cauce.governance;

import dev.cauce.governance.audit.AuditDrainerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Module configuration for cauce-governance. Enables scheduling at the framework level
 * (self-contained — duplicating {@code @EnableScheduling} with other modules is harmless);
 * the drainer bean carries its own {@code @ConditionalOnProperty} so the job is individually
 * opt-out, mirroring {@code OrchestrationConfig}.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AuditDrainerProperties.class)
public class GovernanceConfig {
}
