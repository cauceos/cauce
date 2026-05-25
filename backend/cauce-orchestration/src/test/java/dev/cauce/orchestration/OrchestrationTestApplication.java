package dev.cauce.orchestration;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Spring Boot configuration for cauce-orchestration integration tests. Mirrors the
 * application's scanning so beans across the modules are wired: components/services/the
 * aspect ({@code dev.cauce}), JPA entities, and Spring Data repositories.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan("dev.cauce")
@EntityScan("dev.cauce")
@EnableJpaRepositories("dev.cauce")
public class OrchestrationTestApplication {
}
