package dev.cauce.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.apikey.ApiKey;
import dev.cauce.core.apikey.ApiKeyAlreadyRevokedException;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.tenant.MissingTenantContextException;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tenant.TenantNotFoundException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
 * End-to-end tests for ApiKeyService and the api_keys RLS policy. The Spring
 * datasource connects as the container superuser (mirroring the current app), so RLS
 * filtering is verified through a dedicated restricted role on data created by the
 * service.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ApiKeyServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ApiKeyHasher apiKeyHasher;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private Tenant operator;
    private Tenant partner;
    private Tenant partner2;
    private Tenant clientA;
    private Tenant clientB;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE TABLE api_keys, pending_invocations, messages, "
                + "conversations, agents, tenants CASCADE");
        // cauce_app and its grants come from Flyway migration V10; tests SET ROLE to it.
        TenantContext.clear();

        operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        partner = tenantService.createPartner("Partner", operator.id());
        partner2 = tenantService.createPartner("Partner 2", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        clientA = tenantService.createClient("Client A", partner.id());
        clientB = tenantService.createClient("Client B", partner.id());
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createApiKey_persistsAndReturnsPlaintextOnce() {
        TenantContext.setCurrentTenantId(clientA.id());
        try {
            ApiKeyCreationResult result = apiKeyService.createApiKey(clientA.id(), "Production");

            assertThat(result.plaintextKey()).startsWith("ck_");
            assertThat(result.apiKey().tenantId()).isEqualTo(clientA.id());

            // The hash stored in the database is NEVER the plaintext.
            String storedHash = jdbc.queryForObject(
                    "SELECT key_hash FROM api_keys WHERE id = ?",
                    String.class, result.apiKey().id());
            assertThat(storedHash).isNotEqualTo(result.plaintextKey());
            assertThat(storedHash).doesNotContain(result.plaintextKey());

            // But the hash does verify against the plaintext via the bcrypt hasher.
            assertThat(apiKeyHasher.matches(result.plaintextKey(), storedHash)).isTrue();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void createApiKey_whenTenantNotFound_throwsTenantNotFound() {
        TenantContext.setCurrentTenantId(clientA.id());
        try {
            assertThatThrownBy(() -> apiKeyService.createApiKey(UUID.randomUUID(), "name"))
                    .isInstanceOf(TenantNotFoundException.class);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void createApiKey_byPartnerForItsClient_succeeds() {
        TenantContext.setCurrentTenantId(partner.id());
        try {
            ApiKeyCreationResult result = apiKeyService.createApiKey(clientA.id(), "delegated");

            assertThat(result.apiKey().tenantId()).isEqualTo(clientA.id());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void revokeApiKey_marksAsRevokedAndPersists() {
        ApiKey created = createKeyAs(clientA.id(), "k");

        TenantContext.setCurrentTenantId(clientA.id());
        try {
            ApiKey revoked = apiKeyService.revokeApiKey(created.id());

            assertThat(revoked.isRevoked()).isTrue();
            String dbStatus = jdbc.queryForObject(
                    "SELECT CASE WHEN revoked_at IS NULL THEN 'active' ELSE 'revoked' END "
                            + "FROM api_keys WHERE id = ?", String.class, created.id());
            assertThat(dbStatus).isEqualTo("revoked");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void revokeApiKey_twice_throwsAlreadyRevoked() {
        ApiKey created = createKeyAs(clientA.id(), "k");
        TenantContext.setCurrentTenantId(clientA.id());
        try {
            apiKeyService.revokeApiKey(created.id());

            assertThatThrownBy(() -> apiKeyService.revokeApiKey(created.id()))
                    .isInstanceOf(ApiKeyAlreadyRevokedException.class);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void apiKeyCreatedViaService_isFilteredByRlsHierarchy() throws SQLException {
        ApiKey created = createKeyAs(clientA.id(), "k");

        assertThat(countVisibleAs(clientA.id())).isEqualTo(1);  // owner sees it
        assertThat(countVisibleAs(partner.id())).isEqualTo(1);  // its partner sees it
        assertThat(countVisibleAs(operator.id())).isEqualTo(1); // its operator sees it
        assertThat(countVisibleAs(clientB.id())).isZero();      // sibling client does not
        assertThat(countVisibleAs(partner2.id())).isZero();     // sibling partner does not
        assertThat(countVisibleAs(null)).isZero();              // no context => nothing
        assertThat(created).isNotNull();
    }

    @Test
    void uuidV7Ordering_whenCreatingMultiple_thenIdsAreTimeOrdered() throws InterruptedException {
        List<UUID> creationOrder = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            creationOrder.add(createKeyAs(clientA.id(), "k" + i).id());
            Thread.sleep(2);
        }

        List<UUID> byId = jdbc.queryForList("SELECT id FROM api_keys ORDER BY id", UUID.class);

        assertThat(byId).containsExactlyElementsOf(creationOrder);
    }

    @Test
    void createApiKey_withoutTenantContext_throwsMissingTenantContext() {
        TenantContext.clear();

        assertThatThrownBy(() -> apiKeyService.createApiKey(clientA.id(), "k"))
                .isInstanceOf(MissingTenantContextException.class);
    }

    private ApiKey createKeyAs(UUID context, String name) {
        TenantContext.setCurrentTenantId(context);
        try {
            return apiKeyService.createApiKey(context, name).apiKey();
        } finally {
            TenantContext.clear();
        }
    }

    private long countVisibleAs(UUID context) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE cauce_app");
                if (context != null) {
                    st.execute("SET LOCAL app.current_tenant_id = '" + context + "'");
                }
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM api_keys")) {
                    rs.next();
                    return rs.getLong(1);
                }
            } finally {
                conn.rollback();
            }
        }
    }
}
