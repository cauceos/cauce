package dev.cauce.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers infrastructure for every {@code cauce-api} integration test.
 *
 * <p>Declares Redis as a Spring bean annotated with {@link ServiceConnection}, so Spring Boot
 * wires {@code spring.data.redis.*} automatically and manages the container lifecycle with the
 * application context. Postgres is intentionally NOT declared here: it needs two datasources
 * (least-privilege {@code cauce_app} for runtime, owner for Flyway/bootstrap), so it is a
 * singleton container wired via {@code @DynamicPropertySource} in {@link AbstractApiIntegrationTest}.
 *
 * <p>Redis is provisioned even though no production code uses it yet: the actuator health
 * endpoint aggregates the auto-configured {@code RedisHealthIndicator}, so
 * {@code /actuator/health} only returns 200 when Redis is genuinely reachable.
 *
 * <p>Image versions intentionally match {@code docker-compose.yml} so local and CI runs
 * exercise the same software.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ApiIntegrationTestcontainers {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
    }
}
