package dev.cauce.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers infrastructure for every {@code cauce-api} integration test.
 *
 * <p>Declares the backing services as Spring beans annotated with
 * {@link ServiceConnection}, so Spring Boot wires {@code spring.datasource.*} and
 * {@code spring.data.redis.*} automatically and manages the container lifecycle along
 * with the application context. Because the context is cached per configuration, the
 * containers start once and are reused across all integration tests in the module
 * rather than restarting per class.
 *
 * <p>Redis is provisioned even though no production code uses it yet: the actuator
 * health endpoint aggregates the auto-configured {@code RedisHealthIndicator} (the
 * {@code spring-boot-starter-data-redis} dependency puts Redis on the classpath), so
 * {@code /actuator/health} only returns 200 when Redis is genuinely reachable.
 *
 * <p>Image versions intentionally match {@code docker-compose.yml} so local and CI
 * runs exercise the same software.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ApiIntegrationTestcontainers {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
    }
}
