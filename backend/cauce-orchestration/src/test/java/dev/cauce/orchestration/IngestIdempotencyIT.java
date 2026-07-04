package dev.cauce.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.events.InvocationRequested;
import dev.cauce.orchestration.support.AbstractOrchestrationIntegrationTest;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.TenantService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * Integration tests for idempotent ingest against a real PostgreSQL via Testcontainers,
 * exercising the runtime {@code cauce_app}/RLS path: replay of a committed ingest, the
 * concurrent duplicate race on the V15 unique constraint, and hierarchical visibility of the
 * idempotency rows. Event-silence of the replay is asserted through
 * {@link RecordApplicationEvents} (sequential cases only: it records the test thread).
 */
@RecordApplicationEvents
class IngestIdempotencyIT extends AbstractOrchestrationIntegrationTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private InboundMessageService inboundMessageService;

    @Autowired
    private ApplicationEvents applicationEvents;

    private JdbcTemplate jdbc;

    private Tenant operator;
    private Tenant partner;
    private Tenant clientA;
    private Tenant clientB;
    private Agent agent; // owned by clientA

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(adminDataSource);
        truncateAll();
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
    void ingest_sameKeyTwiceSequential_deduplicates() {
        InboundMessageResult first = ingestAs(clientA.id(), "user-1", "Hola", "wamid-1");
        InboundMessageResult replay = ingestAs(clientA.id(), "user-1", "Hola", "wamid-1");

        assertThat(replay.conversationId()).isEqualTo(first.conversationId());
        assertThat(replay.messageId()).isEqualTo(first.messageId());
        assertThat(replay.invocationId()).isEqualTo(first.invocationId());

        assertThat(countUserMessages(first.conversationId())).isEqualTo(1);
        assertThat(countInvocations(first.conversationId())).isEqualTo(1);
        assertThat(applicationEvents.stream(InvocationRequested.class).count()).isEqualTo(1);
    }

    @Test
    void ingest_sameKeyConcurrent_yieldsExactlyOneIngest() throws Exception {
        int parallel = 8;
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        CyclicBarrier barrier = new CyclicBarrier(parallel);
        List<Callable<InboundMessageResult>> tasks = new ArrayList<>();
        for (int i = 0; i < parallel; i++) {
            tasks.add(() -> {
                TenantContext.setCurrentTenantId(clientA.id());
                try {
                    barrier.await(5, TimeUnit.SECONDS); // release all callers together to force the race
                    return inboundMessageService.ingest(agent.id(), "api", "user-shared", "Hola",
                            "wamid-shared");
                } finally {
                    TenantContext.clear();
                }
            });
        }

        Set<InboundMessageResult> results = new HashSet<>();
        try {
            for (Future<InboundMessageResult> future : pool.invokeAll(tasks)) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        // All concurrent duplicates converge on the winner's result; losers block on the V15
        // unique index while the original is in flight and then read its committed row.
        assertThat(results).as("concurrent duplicates converge on one result").hasSize(1);
        UUID conversationId = results.iterator().next().conversationId();
        assertThat(countUserMessages(conversationId)).isEqualTo(1);
        assertThat(countInvocations(conversationId)).isEqualTo(1);
        Integer records = jdbc.queryForObject(
                "SELECT count(*) FROM ingest_idempotency_records WHERE agent_id = ?",
                Integer.class, agent.id());
        assertThat(records).isEqualTo(1);
    }

    @Test
    void ingest_differentKeys_ingestsBoth() {
        InboundMessageResult one = ingestAs(clientA.id(), "user-1", "Hola", "wamid-1");
        InboundMessageResult two = ingestAs(clientA.id(), "user-1", "¿Sigues ahí?", "wamid-2");

        assertThat(two.messageId()).isNotEqualTo(one.messageId());
        assertThat(two.conversationId()).isEqualTo(one.conversationId()); // same OPEN thread
        assertThat(countUserMessages(one.conversationId())).isEqualTo(2);
        assertThat(countInvocations(one.conversationId())).isEqualTo(2);
    }

    @Test
    void ingest_withoutKey_neverDeduplicates() {
        InboundMessageResult first = ingestAs(clientA.id(), "user-1", "Hola", null);
        InboundMessageResult second = ingestAs(clientA.id(), "user-1", "Hola", null);

        assertThat(second.messageId()).isNotEqualTo(first.messageId());
        assertThat(countUserMessages(first.conversationId())).isEqualTo(2);
        assertThat(countInvocations(first.conversationId())).isEqualTo(2);
        Integer records = jdbc.queryForObject(
                "SELECT count(*) FROM ingest_idempotency_records", Integer.class);
        assertThat(records).isZero();
    }

    @Test
    void idempotencyRecords_respectHierarchicalVisibility() {
        ingestAs(clientA.id(), "user-1", "Hola", "wamid-1");

        assertThat(countAs("ingest_idempotency_records", operator.id())).isEqualTo(1);
        assertThat(countAs("ingest_idempotency_records", partner.id())).isEqualTo(1);
        assertThat(countAs("ingest_idempotency_records", clientA.id())).isEqualTo(1);
        assertThat(countAs("ingest_idempotency_records", clientB.id())).isZero();
        assertThat(countAs("ingest_idempotency_records", null)).isZero();
    }

    private InboundMessageResult ingestAs(UUID context, String externalIdentityRef, String content,
                                          String idempotencyKey) {
        TenantContext.setCurrentTenantId(context);
        try {
            return inboundMessageService.ingest(agent.id(), "api", externalIdentityRef, content,
                    idempotencyKey);
        } finally {
            TenantContext.clear();
        }
    }

    private int countUserMessages(UUID conversationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ? AND role = 'USER'",
                Integer.class, conversationId);
    }

    private int countInvocations(UUID conversationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pending_invocations WHERE conversation_id = ?",
                Integer.class, conversationId);
    }
}
