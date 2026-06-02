package dev.cauce.api.support;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for {@code cauce-api} integration tests.
 *
 * <p>Boots the full application under the {@code test} profile with MockMvc wired. Postgres
 * is a JVM-wide singleton container (started once, reused across every test class via the
 * Spring context cache) so the two datasources can be wired explicitly; Redis is provided as
 * a {@link org.springframework.boot.testcontainers.service.connection.ServiceConnection}
 * bean in {@link ApiIntegrationTestcontainers}.
 *
 * <p><strong>RLS is exercised through the real application path.</strong> The
 * {@code spring.datasource} (the runtime connection for JPA and the repositories) is the
 * least-privilege {@code cauce_app} role created by the {@code testcontainers/cauce-app-role.sql}
 * init script and granted by Flyway V10, so cross-tenant reads are filtered by the database.
 * The container superuser is wired only as {@code cauce.admin.datasource} for Flyway and the
 * operator bootstrap, which must bypass RLS.
 *
 * <p>Deliberately generic — it carries no auth-, tenant-, or endpoint-specific wiring — so
 * any integration test in the module can extend it and inherit the same containers and the
 * {@link #truncateAll()} reset helper.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ApiIntegrationTestcontainers.class)
public abstract class AbstractApiIntegrationTest {

    /** Matches the LOGIN role created by {@code testcontainers/cauce-app-role.sql}. */
    private static final String APP_USERNAME = "cauce_app";
    private static final String APP_PASSWORD = "cauce_app_test";

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
                    .withInitScript("testcontainers/cauce-app-role.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void dataSources(DynamicPropertyRegistry registry) {
        // Runtime application connection: the least-privilege cauce_app role, so RLS is
        // enforced through the real application path.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USERNAME);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        // Privileged owner connection: Flyway migrations and operator bootstrap (bypass RLS).
        registry.add("cauce.admin.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("cauce.admin.datasource.username", POSTGRES::getUsername);
        registry.add("cauce.admin.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    @Qualifier("adminDataSource")
    protected DataSource adminDataSource;

    /**
     * Resets all tenant-scoped tables via the privileged owner connection. cauce_app is
     * intentionally not granted TRUNCATE, so tests clean up through the owner.
     */
    protected void truncateAll() {
        new JdbcTemplate(adminDataSource).execute(
                "TRUNCATE TABLE api_keys, pending_invocations, messages, conversations, agents, tenants CASCADE");
    }
}
