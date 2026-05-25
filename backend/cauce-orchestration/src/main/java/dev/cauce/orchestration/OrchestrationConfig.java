package dev.cauce.orchestration;

import dev.cauce.orchestration.context.ContextBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Module configuration for cauce-orchestration beans.
 *
 * <p>{@link ContextBuilder} is deliberately a framework-free, pure-logic class, so it is
 * exposed as a Spring bean here (rather than annotated {@code @Component}) to keep it
 * injectable into {@code OrchestratorService} while remaining decoupled from Spring.
 */
@Configuration
public class OrchestrationConfig {

    @Bean
    public ContextBuilder contextBuilder() {
        return new ContextBuilder();
    }
}
