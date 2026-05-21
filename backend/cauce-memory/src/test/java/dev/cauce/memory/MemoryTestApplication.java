package dev.cauce.memory;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal Spring Boot configuration so {@code @SpringBootTest} can bootstrap an
 * application context for cauce-memory's integration tests. {@code @EnableAutoConfiguration}
 * registers {@code dev.cauce.memory} for entity scanning, so {@code TenantEntity}
 * is picked up and validated against the Flyway-created schema.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class MemoryTestApplication {
}
