package dev.cauce.orchestration.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for cauce-orchestration integration tests that exercises the real runtime path: the
 * application connects as the least-privilege {@code cauce_app} role, so Row-Level Security is
 * enforced and the worker's cross-tenant claim/reap go through the V12 SECURITY DEFINER
 * functions. A JVM-wide singleton Postgres container (reused across classes via the Spring
 * context cache) creates {@code cauce_app} via its init script; Flyway and the operator
 * bootstrap use the owner connection wired as {@code cauce.admin.datasource}.
 *
 * <p>Subclasses keep their own {@code @TestPropertySource}/{@code @Import} (e.g. the worker IT
 * enabling the scheduler, or the mock LLM provider). Raw DB setup/introspection that must
 * bypass RLS (TRUNCATE, seeding rows, reading another tenant's row) goes through
 * {@link #adminDataSource}; the autowired {@code @Primary} {@code DataSource} is cauce_app and
 * exercises RLS.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractOrchestrationIntegrationTest {

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
        // Runtime application connection: the least-privilege cauce_app role (subject to RLS).
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

    @Autowired
    @Qualifier("appDataSource")
    protected DataSource appDataSource;

    /**
     * Resets all tenant-scoped tables via the privileged owner connection. cauce_app is
     * intentionally not granted TRUNCATE, so tests clean up through the owner.
     */
    protected void truncateAll() {
        new JdbcTemplate(adminDataSource).execute(
                "TRUNCATE TABLE api_keys, pending_invocations, messages, conversations, agents, tenants CASCADE");
    }

    /**
     * Counts rows of {@code table} visible under {@code context}, querying as the runtime
     * cauce_app role so Row-Level Security applies. A null context sets no GUC, so RLS
     * fail-closes to zero. {@code table} is a test-controlled constant, never user input.
     */
    protected long countAs(String table, UUID context) {
        try (Connection conn = appDataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                if (context != null) {
                    st.execute("SET LOCAL app.current_tenant_id = '" + context + "'");
                }
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
                    rs.next();
                    return rs.getLong(1);
                }
            } finally {
                conn.rollback();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Runs {@code sql} (an UPDATE/DELETE) as the runtime cauce_app role under {@code context}
     * and returns the affected-row count, then rolls back. Used to prove containment: an
     * attempt to touch another tenant's row affects zero rows because RLS hides it.
     */
    protected int updateAs(UUID context, String sql) {
        try (Connection conn = appDataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                if (context != null) {
                    st.execute("SET LOCAL app.current_tenant_id = '" + context + "'");
                }
                return st.executeUpdate(sql);
            } finally {
                conn.rollback();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
