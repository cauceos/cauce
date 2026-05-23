package dev.cauce.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.tenant.MissingTenantContextException;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tenant.Tier;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests for tenant creation through the service and aspect. The Spring
 * datasource connects as the container superuser (mirroring the current app), so
 * RLS filtering is verified through a dedicated restricted role on data created by
 * the service; the aspect's set_config is verified directly via {@link RlsProbeService}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TenantServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private TenantService tenantService;

    @Autowired
    private RlsProbeService rlsProbeService;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE TABLE conversations, agents, tenants CASCADE");
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'cauce_app') "
                + "THEN CREATE ROLE cauce_app; END IF; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO cauce_app");
        jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON tenants TO cauce_app");
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void bootstrapOperator_withoutContext_persistsOperator() {
        Tenant operator = tenantService.bootstrapOperator("Acme Operator");

        assertThat(operator.tier()).isEqualTo(Tier.OPERATOR);
        assertThat(countById(operator.id())).isEqualTo(1);
    }

    @Test
    void createPartner_withoutContext_throwsMissingTenantContext() {
        Tenant operator = tenantService.bootstrapOperator("Op");
        TenantContext.clear();

        assertThatThrownBy(() -> tenantService.createPartner("P", operator.id()))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void createPartner_withOperatorContext_persistsPartner() {
        Tenant operator = tenantService.bootstrapOperator("Op");
        TenantContext.setCurrentTenantId(operator.id());

        Tenant partner = tenantService.createPartner("Partner Co", operator.id());

        assertThat(partner.tier()).isEqualTo(Tier.PARTNER);
        assertThat(partner.parentTenantId()).isEqualTo(operator.id());
        assertThat(countById(partner.id())).isEqualTo(1);
    }

    @Test
    void aspect_setsTenantContextWithinTransaction() {
        UUID actingTenant = UUID.randomUUID();
        TenantContext.setCurrentTenantId(actingTenant);

        assertThat(rlsProbeService.currentContext()).isEqualTo(actingTenant);
    }

    @Test
    void hierarchyCreatedViaService_isFilteredByRlsPerContext() throws SQLException {
        Tenant operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        Tenant partner = tenantService.createPartner("Partner", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        Tenant client = tenantService.createClient("Client", partner.id());
        TenantContext.clear();

        assertThat(countVisibleAs(operator.id())).isEqualTo(3); // operator sees all
        assertThat(countVisibleAs(partner.id())).isEqualTo(2);  // partner sees self + client
        assertThat(countVisibleAs(client.id())).isEqualTo(1);   // client sees only itself
        assertThat(countVisibleAs(null)).isZero();              // no context => nothing
    }

    private Integer countById(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Integer.class, id);
    }

    /** Counts rows visible to {@code context} under the restricted (RLS-enforced) role. */
    private long countVisibleAs(UUID context) throws SQLException {
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
                conn.rollback();
            }
        }
    }
}
