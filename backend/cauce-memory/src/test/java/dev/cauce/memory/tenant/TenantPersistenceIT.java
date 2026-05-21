package dev.cauce.memory.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.tenant.Tenant;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end persistence tests for the tenants schema: Flyway migration, hierarchy
 * constraints/trigger, Row-Level Security, and UUIDv7 ordering. RLS is exercised
 * through a dedicated least-privilege role (the test superuser bypasses RLS).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TenantPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE TABLE tenants");
        // Least-privilege role for RLS tests (idempotent across tests).
        jdbc.execute("DO $$ BEGIN "
                + "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'cauce_app') "
                + "THEN CREATE ROLE cauce_app; END IF; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO cauce_app");
        jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON tenants TO cauce_app");
    }

    @Test
    void testMigrationApplies_whenContainerStarts_thenTenantsTableExists() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'tenants'",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "parent_tenant_id", "tier", "name", "created_at", "updated_at");

        Integer v1 = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1'", Integer.class);
        assertThat(v1).isEqualTo(1);
    }

    @Test
    void testOperatorCannotHaveParent_whenInsertingWithParentId_thenConstraintFails() {
        Tenant operator = Tenant.operator("Op");
        insert(operator);

        assertThatThrownBy(() ->
                insertRaw(UUID.randomUUID(), operator.id(), "OPERATOR", "Bad operator"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void testPartnerRequiresOperatorParent_whenInsertingWithClientParent_thenFails() {
        Tenant operator = Tenant.operator("Op");
        Tenant partner = Tenant.partner("Pa", operator.id());
        Tenant client = Tenant.client("Cl", partner.id());
        insert(operator);
        insert(partner);
        insert(client);

        assertThatThrownBy(() ->
                insertRaw(UUID.randomUUID(), client.id(), "PARTNER", "Bad partner"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("PARTNER parent must be an OPERATOR");
    }

    @Test
    void testClientRequiresPartnerParent_whenInsertingWithOperatorParent_thenFails() {
        Tenant operator = Tenant.operator("Op");
        insert(operator);

        assertThatThrownBy(() ->
                insertRaw(UUID.randomUUID(), operator.id(), "CLIENT", "Bad client"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("CLIENT parent must be a PARTNER");
    }

    @Test
    void testRlsBlocksWithoutTenantContext_whenQueryingWithoutSet_thenZeroRows() {
        insertHierarchy();

        assertThat(countVisibleAs(null)).isZero();
    }

    @Test
    void testRlsRespectsHierarchy_whenSettingOperatorContext_thenSeesPartnersAndClients() {
        Tenant[] h = insertHierarchy(); // [operator, partner, client]

        assertThat(countVisibleAs(h[0].id())).isEqualTo(3); // operator sees all
        assertThat(countVisibleAs(h[1].id())).isEqualTo(2); // partner sees self + client
        assertThat(countVisibleAs(h[2].id())).isEqualTo(1); // client sees only itself
    }

    @Test
    void testUuidV7Ordering_whenCreatingMultipleTenants_thenIdsAreTimeOrdered()
            throws InterruptedException {
        List<UUID> creationOrder = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Tenant t = Tenant.operator("Op " + i);
            insert(t);
            creationOrder.add(t.id());
            Thread.sleep(2); // distinct millisecond => deterministic v7 ordering
        }

        List<UUID> byId = jdbc.queryForList("SELECT id FROM tenants ORDER BY id", UUID.class);

        assertThat(byId).containsExactlyElementsOf(creationOrder);
    }

    // --- helpers ---

    private Tenant[] insertHierarchy() {
        Tenant operator = Tenant.operator("Operator");
        Tenant partner = Tenant.partner("Partner", operator.id());
        Tenant client = Tenant.client("Client", partner.id());
        insert(operator);
        insert(partner);
        insert(client);
        return new Tenant[] {operator, partner, client};
    }

    private void insert(Tenant t) {
        if (t.parentTenantId() == null) {
            jdbc.update("INSERT INTO tenants (id, parent_tenant_id, tier, name, created_at, updated_at) "
                    + "VALUES (?, NULL, ?, ?, ?, ?)",
                    t.id(), t.tier().name(), t.name(),
                    Timestamp.from(t.createdAt()), Timestamp.from(t.updatedAt()));
        } else {
            jdbc.update("INSERT INTO tenants (id, parent_tenant_id, tier, name, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)",
                    t.id(), t.parentTenantId(), t.tier().name(), t.name(),
                    Timestamp.from(t.createdAt()), Timestamp.from(t.updatedAt()));
        }
    }

    private void insertRaw(UUID id, UUID parent, String tier, String name) {
        jdbc.update("INSERT INTO tenants (id, parent_tenant_id, tier, name, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, now(), now())", id, parent, tier, name);
    }

    /**
     * Runs {@code SELECT count(*) FROM tenants} as the least-privilege role with the
     * given tenant context, so RLS actually applies. A null context sets nothing.
     */
    private long countVisibleAs(UUID context) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE cauce_app");
                if (context != null) {
                    st.execute("SET LOCAL app.current_tenant_id = '" + context + "'");
                }
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM tenants")) {
                    rs.next();
                    return rs.getLong(1);
                }
            } finally {
                conn.rollback(); // reverts SET ROLE and SET LOCAL; cleans the pooled connection
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
