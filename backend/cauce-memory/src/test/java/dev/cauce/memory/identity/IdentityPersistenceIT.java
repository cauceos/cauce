package dev.cauce.memory.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.identity.Identity;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.memory.tenant.TenantMapper;
import dev.cauce.memory.tenant.TenantRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Persistence tests for identities (V25): the insert-first upsert is race-safe on the
 * {@code identities_key} constraint, the key is per tenant, the {@code kind} CHECK guards the
 * vocabulary, and Row-Level Security filters and admits rows by the tenant hierarchy. RLS is
 * exercised through the least-privilege {@code cauce_app} role (the test superuser bypasses
 * RLS, so it is used only to seed and to drive the repository).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class IdentityPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private IdentityRepository identityRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final TenantMapper tenantMapper = new TenantMapper();
    private final IdentityMapper identityMapper = new IdentityMapper();

    private UUID operatorId;
    private UUID partnerId;
    private UUID clientId;
    private UUID clientBId;

    @BeforeEach
    void setUp() {
        new JdbcTemplate(dataSource).execute("TRUNCATE TABLE audit_chain_heads, audit_log_entries, audit_outbox, llm_usage_records, channel_configs, api_keys, ingest_idempotency_records, "
                + "pending_invocations, "
                + "messages, conversations, identities, agents, tenants CASCADE");

        Tenant operator = Tenant.operator("Operator");
        Tenant partner = Tenant.partner("Partner", operator.id());
        Tenant clientA = Tenant.client("Client A", partner.id());
        Tenant clientB = Tenant.client("Client B", partner.id());
        tenantRepository.save(tenantMapper.toEntity(operator));
        tenantRepository.save(tenantMapper.toEntity(partner));
        tenantRepository.save(tenantMapper.toEntity(clientA));
        tenantRepository.save(tenantMapper.toEntity(clientB));
        operatorId = operator.id();
        partnerId = partner.id();
        clientId = clientA.id();
        clientBId = clientB.id();
    }

    @Test
    void insertIfAbsent_createsTheRowOnce_andSkipsTheDuplicate() {
        Identity first = Identity.create(clientId, "telegram", IdentityKind.PROVIDER_USER_ID, "987654321");
        Identity duplicate = Identity.create(clientId, "telegram", IdentityKind.PROVIDER_USER_ID, "987654321");

        assertThat(insertIfAbsent(first)).isEqualTo(1);
        assertThat(insertIfAbsent(duplicate)).isZero();

        Identity stored = identityRepository
                .findByTenantIdAndChannelTypeAndKindAndValue(clientId, "telegram",
                        IdentityKind.PROVIDER_USER_ID, "987654321")
                .map(identityMapper::toDomain)
                .orElseThrow();
        assertThat(stored.id()).isEqualTo(first.id());
        assertThat(stored.createdAt()).isNotNull();
        assertThat(identityRepository.count()).isEqualTo(1);
    }

    @Test
    void sameKey_underAnotherTenant_isADistinctIdentity() {
        Identity inA = Identity.create(clientId, "api", IdentityKind.CLIENT_REFERENCE, "user-1");
        Identity inB = Identity.create(clientBId, "api", IdentityKind.CLIENT_REFERENCE, "user-1");

        assertThat(insertIfAbsent(inA)).isEqualTo(1);
        assertThat(insertIfAbsent(inB)).isEqualTo(1);

        assertThat(identityRepository.findByTenantIdAndChannelTypeAndKindAndValue(
                clientId, "api", IdentityKind.CLIENT_REFERENCE, "user-1"))
                .map(IdentityEntity::getId).contains(inA.id());
        assertThat(identityRepository.findByTenantIdAndChannelTypeAndKindAndValue(
                clientBId, "api", IdentityKind.CLIENT_REFERENCE, "user-1"))
                .map(IdentityEntity::getId).contains(inB.id());
    }

    @Test
    void differentKind_orChannel_withTheSameValue_areDistinctIdentities() {
        assertThat(insertIfAbsent(Identity.create(clientId, "whatsapp", IdentityKind.PHONE_NUMBER, "+34600"))).isEqualTo(1);
        assertThat(insertIfAbsent(Identity.create(clientId, "voice", IdentityKind.PHONE_NUMBER, "+34600"))).isEqualTo(1);
        assertThat(insertIfAbsent(Identity.create(clientId, "whatsapp", IdentityKind.PROVIDER_USER_ID, "+34600"))).isEqualTo(1);

        assertThat(identityRepository.count()).isEqualTo(3);
    }

    @Test
    void unknownKind_isRejectedByTheCheckConstraint() {
        assertThatThrownBy(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO identities (id, tenant_id, channel_type, kind, value) VALUES (?, ?, 'api', 'HANDLE', 'x')")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, clientId);
                ps.executeUpdate();
            }
        }).isInstanceOf(SQLException.class).hasMessageContaining("identities_kind_check");
    }

    @Test
    void save_roundTripsThroughJpa() {
        Identity identity = Identity.create(clientId, "email", IdentityKind.EMAIL_ADDRESS, "someone@example.org");

        identityRepository.save(identityMapper.toEntity(identity));

        Identity reloaded = identityRepository.findById(identity.id()).map(identityMapper::toDomain).orElseThrow();
        assertThat(reloaded).isEqualTo(identity);
        assertThat(reloaded.kind()).isEqualTo(IdentityKind.EMAIL_ADDRESS);
        assertThat(reloaded.value()).isEqualTo("someone@example.org");
    }

    @Test
    void identities_areFilteredByRlsHierarchy() throws SQLException {
        insertIfAbsent(Identity.create(clientId, "api", IdentityKind.CLIENT_REFERENCE, "user-1"));

        assertThat(countVisibleAs(clientId)).isEqualTo(1);   // owning client
        assertThat(countVisibleAs(partnerId)).isEqualTo(1);  // ancestor partner sees it
        assertThat(countVisibleAs(operatorId)).isEqualTo(1); // ancestor operator sees it
        assertThat(countVisibleAs(clientBId)).isZero();      // sibling client blocked
        assertThat(countVisibleAs(null)).isZero();           // no context => nothing
    }

    @Test
    void insert_underPartnerContext_forItsClient_isAdmittedByRls() throws SQLException {
        // A partner ingesting on behalf of its client mints the CLIENT's identity: the row's
        // tenant_id is the client's, and the policy's WITH CHECK admits it because the client is
        // visible to the partner.
        insertAs(partnerId, clientId, "user-1");

        assertThat(countVisibleAs(clientId)).isEqualTo(1);
    }

    @Test
    void insert_underSiblingContext_isRejectedByRls() {
        assertThatThrownBy(() -> insertAs(clientBId, clientId, "user-1"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security");
    }

    @Test
    void insert_withoutTenantContext_isRejectedByRls() {
        assertThatThrownBy(() -> insertAs(null, clientId, "user-1"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security");
    }

    /** The native upsert is a modifying statement and needs a (read-write) transaction. */
    private int insertIfAbsent(Identity identity) {
        Integer inserted = transactionTemplate.execute(status -> identityRepository.insertIfAbsent(
                identity.id(), identity.tenantId(), identity.channelType(), identity.kind().name(),
                identity.value()));
        return inserted == null ? -1 : inserted;
    }

    /**
     * Counts visible identities as the least-privilege role under the given tenant context, so
     * RLS actually applies. A null context sets nothing.
     */
    private long countVisibleAs(UUID context) throws SQLException {
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
                conn.rollback(); // reverts SET ROLE and SET LOCAL; cleans the pooled connection
            }
        }
    }

    /**
     * Inserts an {@code api} / {@code CLIENT_REFERENCE} identity owned by {@code ownerTenant} as
     * the least-privilege role under {@code context}, committing on success so the row is
     * observable afterwards. A null context sets nothing.
     */
    private void insertAs(UUID context, UUID ownerTenant, String value) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL ROLE cauce_app");
                if (context != null) {
                    st.execute("SET LOCAL app.current_tenant_id = '" + context + "'");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO identities (id, tenant_id, channel_type, kind, value) "
                                + "VALUES (?, ?, 'api', 'CLIENT_REFERENCE', ?)")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, ownerTenant);
                    ps.setString(3, value);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.rollback(); // reverts SET ROLE and SET LOCAL; cleans the pooled connection
            }
        }
    }
}
