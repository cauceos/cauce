package dev.cauce.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.conversation.InvalidChannelTypeException;
import dev.cauce.core.identity.Identity;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.tenant.MissingTenantContextException;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
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
 * End-to-end tests for IdentityService and the identities RLS policy. The Spring datasource
 * connects as the container superuser (mirroring the other tenancy ITs), so RLS filtering is
 * verified through the dedicated restricted role on data created by the service. An identity
 * is visible iff its owning tenant is visible.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class IdentityServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private TenantService tenantService;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private Tenant operator;
    private Tenant partner;
    private Tenant clientA;
    private Tenant clientB;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE TABLE audit_chain_heads, audit_log_entries, audit_outbox, llm_usage_records, channel_configs, api_keys, ingest_idempotency_records, pending_invocations, messages, conversations, identities, agents, tenants CASCADE");
        TenantContext.clear();

        operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        partner = tenantService.createPartner("Partner", operator.id());
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
    void resolveOrCreate_whenAbsent_persistsTheIdentity_ownedByTheGivenTenant() {
        TenantContext.setCurrentTenantId(clientA.id());

        Identity identity = identityService.resolveOrCreate(
                clientA.id(), "telegram", IdentityKind.PROVIDER_USER_ID, "987654321");

        assertThat(identity.tenantId()).isEqualTo(clientA.id());
        assertThat(identity.channelType()).isEqualTo("telegram");
        assertThat(identity.kind()).isEqualTo(IdentityKind.PROVIDER_USER_ID);
        assertThat(identity.value()).isEqualTo("987654321");
        assertThat(identity.id().version()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM identities WHERE id = ?", UUID.class,
                identity.id())).isEqualTo(clientA.id());
    }

    @Test
    void resolveOrCreate_twice_withTheSameKey_returnsTheSameIdentity() {
        TenantContext.setCurrentTenantId(clientA.id());

        Identity first = identityService.resolveOrCreate(clientA.id(), "api", IdentityKind.CLIENT_REFERENCE, "user-1");
        Identity again = identityService.resolveOrCreate(clientA.id(), "api", IdentityKind.CLIENT_REFERENCE, " user-1 ");

        assertThat(again).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identities", Integer.class)).isEqualTo(1);
    }

    @Test
    void resolveOrCreate_sameValue_acrossKindOrChannel_mintsDistinctIdentities() {
        TenantContext.setCurrentTenantId(clientA.id());

        Identity onWhatsapp = identityService.resolveOrCreate(clientA.id(), "whatsapp", IdentityKind.PHONE_NUMBER, "+34600");
        Identity onVoice = identityService.resolveOrCreate(clientA.id(), "voice", IdentityKind.PHONE_NUMBER, "+34600");
        Identity asProviderId = identityService.resolveOrCreate(clientA.id(), "whatsapp", IdentityKind.PROVIDER_USER_ID, "+34600");

        assertThat(onWhatsapp).isNotEqualTo(onVoice);
        assertThat(onWhatsapp).isNotEqualTo(asProviderId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identities", Integer.class)).isEqualTo(3);
    }

    @Test
    void resolveOrCreate_underPartnerContext_forItsClient_mintsTheClientsIdentity() {
        // A partner ingesting on behalf of its client passes the CLIENT's tenant id (the tenant of
        // the agent, read under RLS), never its own.
        TenantContext.setCurrentTenantId(partner.id());

        Identity identity = identityService.resolveOrCreate(
                clientA.id(), "api", IdentityKind.CLIENT_REFERENCE, "user-1");

        assertThat(identity.tenantId()).isEqualTo(clientA.id());

        // ... and the client then resolves the very same identity under its own context.
        TenantContext.setCurrentTenantId(clientA.id());
        assertThat(identityService.resolveOrCreate(clientA.id(), "api", IdentityKind.CLIENT_REFERENCE, "user-1"))
                .isEqualTo(identity);
    }

    @Test
    void resolveOrCreate_sameKey_underSiblingClients_areTwoIdentities() {
        TenantContext.setCurrentTenantId(clientA.id());
        Identity inA = identityService.resolveOrCreate(clientA.id(), "api", IdentityKind.CLIENT_REFERENCE, "user-1");
        TenantContext.setCurrentTenantId(clientB.id());
        Identity inB = identityService.resolveOrCreate(clientB.id(), "api", IdentityKind.CLIENT_REFERENCE, "user-1");

        assertThat(inA).isNotEqualTo(inB);
        assertThat(inA.tenantId()).isEqualTo(clientA.id());
        assertThat(inB.tenantId()).isEqualTo(clientB.id());
    }

    @Test
    void resolveOrCreate_withUnsupportedChannel_throwsInvalidChannelType_andPersistsNothing() {
        TenantContext.setCurrentTenantId(clientA.id());

        assertThatThrownBy(() -> identityService.resolveOrCreate(
                clientA.id(), "discord", IdentityKind.PROVIDER_USER_ID, "42"))
                .isInstanceOf(InvalidChannelTypeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identities", Integer.class)).isZero();
    }

    @Test
    void resolveOrCreate_withoutContext_throwsMissingTenantContext() {
        TenantContext.clear();

        assertThatThrownBy(() -> identityService.resolveOrCreate(
                clientA.id(), "api", IdentityKind.CLIENT_REFERENCE, "user-1"))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void identities_createdByTheService_areFilteredByRlsHierarchy() throws SQLException {
        TenantContext.setCurrentTenantId(clientA.id());
        identityService.resolveOrCreate(clientA.id(), "api", IdentityKind.CLIENT_REFERENCE, "user-1");

        assertThat(countIdentitiesVisibleAs(clientA.id())).isEqualTo(1);   // owner
        assertThat(countIdentitiesVisibleAs(partner.id())).isEqualTo(1);   // ancestor partner
        assertThat(countIdentitiesVisibleAs(operator.id())).isEqualTo(1);  // ancestor operator
        assertThat(countIdentitiesVisibleAs(clientB.id())).isZero();       // sibling client
        assertThat(countIdentitiesVisibleAs(null)).isZero();               // no context
    }

    @Test
    void ids_areTimeOrdered() {
        TenantContext.setCurrentTenantId(clientA.id());

        Identity first = identityService.resolveOrCreate(clientA.id(), "api", IdentityKind.CLIENT_REFERENCE, "user-1");
        Identity second = identityService.resolveOrCreate(clientA.id(), "api", IdentityKind.CLIENT_REFERENCE, "user-2");

        assertThat(first.id().toString()).isLessThan(second.id().toString());
    }

    private long countIdentitiesVisibleAs(UUID context) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE cauce_app");
                if (context != null) {
                    st.execute("SET LOCAL app.current_tenant_id = '" + context + "'");
                }
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM identities")) {
                    rs.next();
                    return rs.getLong(1);
                }
            } finally {
                conn.rollback();
            }
        }
    }
}
