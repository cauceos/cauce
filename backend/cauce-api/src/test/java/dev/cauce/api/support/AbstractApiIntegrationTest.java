package dev.cauce.api.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for {@code cauce-api} integration tests.
 *
 * <p>Boots the full application under the {@code test} profile with MockMvc wired and
 * the shared {@link ApiIntegrationTestcontainers} (Postgres + Redis) imported. Tests
 * are self-contained: they do not require an external {@code docker-compose} stack.
 *
 * <p>Deliberately generic — it carries no auth-, tenant-, or endpoint-specific wiring —
 * so any integration test in the module (security filters, REST controllers for Tenant,
 * Agent, Conversation, Message, …) can extend it and inherit the same containers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ApiIntegrationTestcontainers.class)
public abstract class AbstractApiIntegrationTest {
}
