package dev.cauce.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.core.UuidGenerator;
import dev.cauce.core.agent.Agent;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.conversation.ConversationStatus;
import dev.cauce.core.conversation.InvalidConversationTransitionException;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.tenant.MissingTenantContextException;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.memory.conversation.ConversationRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests for ConversationService and the conversations RLS policy. The Spring
 * datasource connects as the container superuser (mirroring the current app), so RLS
 * filtering is verified through a dedicated restricted role on data created by the
 * service. Visibility is inherited transitively: a conversation is visible iff its
 * agent is visible iff the agent's tenant is visible.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ConversationServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private Tenant operator;
    private Tenant partner;
    private Tenant clientA;
    private Tenant clientB;
    private Agent agent; // owned by clientA

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE TABLE audit_chain_heads, audit_log_entries, audit_outbox, llm_usage_records, channel_configs, api_keys, ingest_idempotency_records, pending_invocations, messages, conversations, identities, agents, tenants CASCADE");
        // cauce_app and its grants come from Flyway migration V10; tests SET ROLE to it.
        TenantContext.clear();

        operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        partner = tenantService.createPartner("Partner", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        clientA = tenantService.createClient("Client A", partner.id());
        clientB = tenantService.createClient("Client B", partner.id());
        TenantContext.setCurrentTenantId(clientA.id());
        agent = agentService.createAgent(clientA.id(), "DentalBot",
                "You are a dentist receptionist.", "anthropic", "claude-sonnet-4-7");
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void startConversation_forVisibleAgent_persistsOpenConversation() {
        Conversation conversation = startForClientA("user-1");

        assertThat(conversation.status()).isEqualTo(ConversationStatus.OPEN);
        assertThat(conversation.agentId()).isEqualTo(agent.id());
        assertThat(conversation.closedAt()).isNull();
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE id = ?", Integer.class, conversation.id());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void startConversation_withoutContext_throwsMissingTenantContext() {
        TenantContext.clear();

        assertThatThrownBy(() -> conversationService.startConversation(agent.id(), "api",
                IdentityKind.CLIENT_REFERENCE, "user-1", null))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void startConversation_underPartnerContext_mintsTheIdentityInTheAgentsTenant() {
        // B2B2B: the partner operates on behalf of its client (e.g. an inbound webhook), so RLS
        // makes the client's agent visible; the identity belongs to the agent's tenant (the
        // client), never to the acting partner.
        TenantContext.setCurrentTenantId(partner.id());
        Conversation conversation;
        try {
            conversation = conversationService.startConversation(agent.id(), "api",
                    IdentityKind.CLIENT_REFERENCE, "user-1", null);
            assertThat(conversation.agentId()).isEqualTo(agent.id());
        } finally {
            TenantContext.clear();
        }
        UUID identityTenant = jdbc.queryForObject("SELECT tenant_id FROM identities WHERE id = ?",
                UUID.class, conversation.identityId());
        assertThat(identityTenant).isEqualTo(clientA.id());
    }

    @Test
    void conversationsCreatedViaService_areFilteredByRlsHierarchy() throws SQLException {
        startForClientA("user-1");

        assertThat(countConversationsVisibleAs(clientA.id())).isEqualTo(1);  // owner sees it
        assertThat(countConversationsVisibleAs(partner.id())).isEqualTo(1);  // its partner sees it
        assertThat(countConversationsVisibleAs(operator.id())).isEqualTo(1); // its operator sees it
        assertThat(countConversationsVisibleAs(clientB.id())).isZero();      // sibling client does not
        assertThat(countConversationsVisibleAs(null)).isZero();              // no context => nothing
    }

    @Test
    void getConversation_returnsConversationVisibleToContext() {
        UUID configId = seedChannelConfig("telegram");
        Conversation created = asClientA(() -> conversationService.startConversation(agent.id(), "telegram",
                IdentityKind.PROVIDER_USER_ID, "987654321", configId));

        TenantContext.setCurrentTenantId(clientA.id());
        try {
            Optional<Conversation> found = conversationService.getConversation(created.id());
            assertThat(found).isPresent();
            assertThat(found.get().id()).isEqualTo(created.id());
            assertThat(found.get().identityId()).isEqualTo(created.identityId());
            assertThat(found.get().channelConfigId()).contains(configId);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void findActiveConversation_returnsOnlyTheOpenConversationForTheKey() {
        Conversation open = startForClientA("user-1");
        Conversation closed = startForClientA("user-2");
        asClientA(() -> conversationService.closeConversation(closed.id()));

        TenantContext.setCurrentTenantId(clientA.id());
        try {
            assertThat(conversationService.findActiveConversation(agent.id(), open.identityId(), null))
                    .get()
                    .satisfies(c -> {
                        assertThat(c.id()).isEqualTo(open.id());
                        assertThat(c.status()).isEqualTo(ConversationStatus.OPEN);
                    });
            // Only a CLOSED conversation exists for this identity => no active one.
            assertThat(conversationService.findActiveConversation(agent.id(), closed.identityId(), null))
                    .isEmpty();
            // The same identity through a channel config it never used => no active one.
            assertThat(conversationService.findActiveConversation(agent.id(), open.identityId(),
                    UUID.randomUUID()))
                    .isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void resolveOrStartConversation_reusesOpenThreadAndCreatesOnePerIdentity() {
        TenantContext.setCurrentTenantId(clientA.id());
        try {
            Conversation first = resolveApi("user-1");
            // Same (agent, identity, no config) => the same open conversation is reused.
            Conversation again = resolveApi("user-1");
            assertThat(again.id()).isEqualTo(first.id());
            // A different identity => a distinct conversation.
            Conversation other = resolveApi("user-2");
            assertThat(other.id()).isNotEqualTo(first.id());
            assertThat(other.identityId()).isNotEqualTo(first.identityId());
        } finally {
            TenantContext.clear();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identities", Integer.class)).isEqualTo(2);
    }

    @Test
    void resolveOrStartConversation_sameIdentityThroughTwoConfigs_opensTwoThreads() {
        // PC-1: the config is the door, the identity is the person. One person reaching the same
        // agent through two bots is one identity and two threads.
        UUID botA = seedChannelConfig("telegram");
        UUID botB = seedChannelConfig("telegram");

        TenantContext.setCurrentTenantId(clientA.id());
        try {
            Conversation viaA = resolveTelegram("987654321", botA);
            Conversation viaB = resolveTelegram("987654321", botB);
            Conversation viaAAgain = resolveTelegram("987654321", botA);

            assertThat(viaB.identityId()).isEqualTo(viaA.identityId());
            assertThat(viaB.id()).isNotEqualTo(viaA.id());
            assertThat(viaAAgain.id()).isEqualTo(viaA.id());
            assertThat(viaA.channelConfigId()).contains(botA);
            assertThat(viaB.channelConfigId()).contains(botB);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void insertOpenConversationIfAbsent_secondInsertForSameOpenKeyIsSkipped_evenWithANullConfig() {
        // Two sequential committed transactions: the first creates the OPEN thread; the second
        // attempt for the same (agent, identity, NULL config) is refused by the V26 partial unique
        // index, which is NULLS NOT DISTINCT, and returns 0 (ON CONFLICT DO NOTHING).
        UUID identityId = asClientA(() -> resolveApi("seed")).identityId();
        jdbc.update("UPDATE conversations SET status = 'CLOSED', closed_at = now()");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Integer first = tx.execute(status -> conversationRepository.insertOpenConversationIfAbsent(
                UuidGenerator.newV7(), agent.id(), identityId, null));
        Integer second = tx.execute(status -> conversationRepository.insertOpenConversationIfAbsent(
                UuidGenerator.newV7(), agent.id(), identityId, null));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(countOpen(identityId)).isEqualTo(1);
    }

    @Test
    void resolveOrStartConversation_underConcurrency_yieldsExactlyOneOpenThread_forTheApiChannel()
            throws Exception {
        Set<UUID> returnedIds = race(() -> resolveApi("user-shared").id());

        // All concurrent callers converge on one identity and one conversation: the identities
        // key and the NULLS NOT DISTINCT open-thread index both arbitrate, so a NULL config
        // cannot split the thread.
        assertThat(returnedIds).as("concurrent callers converge on one conversation").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identities", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversations WHERE status = 'OPEN'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void resolveOrStartConversation_underConcurrency_yieldsExactlyOneOpenThread_throughAConfig()
            throws Exception {
        UUID bot = seedChannelConfig("telegram");

        Set<UUID> returnedIds = race(() -> resolveTelegram("987654321", bot).id());

        assertThat(returnedIds).as("concurrent callers converge on one conversation").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identities", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversations WHERE status = 'OPEN'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void uuidV7Ordering_whenCreatingMultipleConversations_thenIdsAreTimeOrdered()
            throws InterruptedException {
        TenantContext.setCurrentTenantId(clientA.id());
        List<UUID> creationOrder = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            creationOrder.add(conversationService.startConversation(agent.id(), "api",
                    IdentityKind.CLIENT_REFERENCE, "user-" + i, null).id());
            Thread.sleep(2);
        }
        TenantContext.clear();

        List<UUID> byId = jdbc.queryForList("SELECT id FROM conversations ORDER BY id", UUID.class);

        assertThat(byId).containsExactlyElementsOf(creationOrder);
    }

    @Test
    void lifecycle_startEscalateCloseArchive_persistsEachStateInDb() {
        Conversation conv = startForClientA("user-1");

        asClientA(() -> conversationService.escalateConversation(conv.id()));
        assertThat(readStatus(conv.id())).isEqualTo("ESCALATED");
        assertThat(readTimestamp(conv.id(), "escalated_at")).isNotNull();

        asClientA(() -> conversationService.closeConversation(conv.id()));
        assertThat(readStatus(conv.id())).isEqualTo("CLOSED");
        assertThat(readTimestamp(conv.id(), "closed_at")).isNotNull();
        assertThat(readTimestamp(conv.id(), "escalated_at")).isNotNull(); // preserved

        asClientA(() -> conversationService.archiveConversation(conv.id()));
        assertThat(readStatus(conv.id())).isEqualTo("ARCHIVED");
        assertThat(readTimestamp(conv.id(), "archived_at")).isNotNull();
        assertThat(readTimestamp(conv.id(), "closed_at")).isNotNull();    // preserved
    }

    @Test
    void closeConversation_underPartnerContext_succeeds() {
        // B2B2B: a partner may close a conversation belonging to one of its clients.
        Conversation conv = startForClientA("user-1");

        TenantContext.setCurrentTenantId(partner.id());
        try {
            Conversation closed = conversationService.closeConversation(conv.id());
            assertThat(closed.status()).isEqualTo(ConversationStatus.CLOSED);
        } finally {
            TenantContext.clear();
        }
        assertThat(readStatus(conv.id())).isEqualTo("CLOSED");
    }

    @Test
    void closeConversation_whenAlreadyClosed_throwsAndLeavesRowUnchanged() {
        Conversation conv = startForClientA("user-1");
        asClientA(() -> conversationService.closeConversation(conv.id()));
        Instant closedAt = readTimestamp(conv.id(), "closed_at");

        // A CLOSED conversation is never reopened or re-closed.
        TenantContext.setCurrentTenantId(clientA.id());
        try {
            assertThatThrownBy(() -> conversationService.closeConversation(conv.id()))
                    .isInstanceOf(InvalidConversationTransitionException.class);
        } finally {
            TenantContext.clear();
        }

        assertThat(readStatus(conv.id())).isEqualTo("CLOSED");
        assertThat(readTimestamp(conv.id(), "closed_at")).isEqualTo(closedAt); // unchanged
    }

    private Conversation startForClientA(String clientReference) {
        return asClientA(() -> conversationService.startConversation(agent.id(), "api",
                IdentityKind.CLIENT_REFERENCE, clientReference, null));
    }

    /** Must run under a tenant context. */
    private Conversation resolveApi(String clientReference) {
        return conversationService.resolveOrStartConversation(agent.id(), "api",
                IdentityKind.CLIENT_REFERENCE, clientReference, null);
    }

    /** Must run under a tenant context. */
    private Conversation resolveTelegram(String chatId, UUID configId) {
        return conversationService.resolveOrStartConversation(agent.id(), "telegram",
                IdentityKind.PROVIDER_USER_ID, chatId, configId);
    }

    /**
     * Releases {@code parallel} first messages for the same key at once, each in its own
     * transaction under client A's context, and returns the distinct conversation ids they got.
     */
    private Set<UUID> race(Callable<UUID> resolve) throws Exception {
        int parallel = 8;
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        CyclicBarrier barrier = new CyclicBarrier(parallel);
        List<Callable<UUID>> tasks = new ArrayList<>();
        for (int i = 0; i < parallel; i++) {
            tasks.add(() -> {
                TenantContext.setCurrentTenantId(clientA.id());
                try {
                    barrier.await(5, TimeUnit.SECONDS); // release all callers together to force the race
                    return resolve.call();
                } finally {
                    TenantContext.clear();
                }
            });
        }
        Set<UUID> returnedIds = new HashSet<>();
        try {
            for (Future<UUID> future : pool.invokeAll(tasks)) {
                returnedIds.add(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        return returnedIds;
    }

    /** Seeds a channel config for the agent directly: cauce-channels owns its service. */
    private UUID seedChannelConfig(String channelType) {
        UUID id = UuidGenerator.newV7();
        jdbc.update("INSERT INTO channel_configs (id, tenant_id, agent_id, channel_type, credential, "
                        + "webhook_secret_hash, status) VALUES (?, ?, ?, ?, 'token', 'hash', 'ACTIVE')",
                id, clientA.id(), agent.id(), channelType);
        return id;
    }

    private <T> T asClientA(Supplier<T> action) {
        TenantContext.setCurrentTenantId(clientA.id());
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private int countOpen(UUID identityId) {
        return jdbc.queryForObject("SELECT count(*) FROM conversations WHERE agent_id = ? AND identity_id = ? "
                + "AND status = 'OPEN'", Integer.class, agent.id(), identityId);
    }

    private String readStatus(UUID conversationId) {
        return jdbc.queryForObject(
                "SELECT status FROM conversations WHERE id = ?", String.class, conversationId);
    }

    /** Reads a nullable timestamp column (column name is a test-controlled literal). */
    private Instant readTimestamp(UUID conversationId, String column) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT " + column + " FROM conversations WHERE id = ?", Timestamp.class, conversationId);
        return ts == null ? null : ts.toInstant();
    }

    private long countConversationsVisibleAs(UUID context) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE cauce_app");
                if (context != null) {
                    st.execute("SET LOCAL app.current_tenant_id = '" + context + "'");
                }
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM conversations")) {
                    rs.next();
                    return rs.getLong(1);
                }
            } finally {
                conn.rollback();
            }
        }
    }
}
