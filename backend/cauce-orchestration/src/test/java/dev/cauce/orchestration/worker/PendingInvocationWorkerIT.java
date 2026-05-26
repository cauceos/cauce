package dev.cauce.orchestration.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tenant.Tenant;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.exception.LlmRateLimitException;
import dev.cauce.llm.model.FinishReason;
import dev.cauce.llm.model.LlmResponse;
import dev.cauce.llm.model.LlmUsage;
import dev.cauce.orchestration.PendingInvocation;
import dev.cauce.orchestration.PendingInvocationService;
import dev.cauce.orchestration.PendingInvocationStatus;
import dev.cauce.orchestration.service.MockLlmProvider;
import dev.cauce.tenancy.AgentService;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.MessageService;
import dev.cauce.tenancy.TenantService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests for {@link PendingInvocationWorker} and {@link PendingInvocationReaper}
 * against a real PostgreSQL database via Testcontainers, with a {@link MockLlmProvider}
 * standing in for the LLM adapter.
 *
 * <p>The worker and reaper are enabled here (overriding the module-wide test default), but
 * their {@code @Scheduled} fixed-delay is set to one hour so the only invocations are the
 * deterministic, manual ones from each test. The reaper timeout is set to 1 second so a
 * single hand-inserted PROCESSING row is immediately reapable.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {
        "cauce.orchestration.worker.enabled=true",
        "cauce.orchestration.worker.poll-interval-ms=3600000",
        "cauce.orchestration.worker.batch-size=5",
        "cauce.orchestration.worker.thread-pool-size=4",
        "cauce.orchestration.worker.retry-base-interval-seconds=30",
        "cauce.orchestration.worker.reaper.enabled=true",
        "cauce.orchestration.worker.reaper.interval-ms=3600000",
        "cauce.orchestration.worker.reaper.timeout-ms=1000"
})
@Import(PendingInvocationWorkerIT.MockProviderConfig.class)
class PendingInvocationWorkerIT {

    @TestConfiguration
    static class MockProviderConfig {
        @Bean
        MockLlmProvider mockLlmProvider() {
            return new MockLlmProvider();
        }
    }

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
    private MessageService messageService;

    @Autowired
    private PendingInvocationService pendingInvocationService;

    @Autowired
    private PendingInvocationWorker worker;

    @Autowired
    private PendingInvocationReaper reaper;

    @Autowired
    private MockLlmProvider mockLlmProvider;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private Tenant operator;
    private Tenant partner;
    private Tenant clientA;
    private Agent agent;
    private Conversation conversation;
    private Message triggerMessage;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE TABLE api_keys, pending_invocations, messages, conversations, agents, tenants CASCADE");
        mockLlmProvider.respondWith(invocation ->
                new LlmResponse("default reply", List.of(), FinishReason.STOP, LlmUsage.of(1, 1)));
        TenantContext.clear();

        operator = tenantService.bootstrapOperator("Operator");
        TenantContext.setCurrentTenantId(operator.id());
        partner = tenantService.createPartner("Partner", operator.id());
        TenantContext.setCurrentTenantId(partner.id());
        clientA = tenantService.createClient("Client A", partner.id());
        TenantContext.setCurrentTenantId(clientA.id());
        agent = agentService.createAgent(clientA.id(), "DentalBot",
                "You are a dentist receptionist.", "anthropic", "claude-sonnet-4-7");
        conversation = conversationService.startConversation(agent.id(), "whatsapp", "+34612345678");
        triggerMessage = messageService.appendMessage(conversation.id(), MessageRole.USER, "Hola");
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void worker_isWiredAndReaperIsWired() {
        assertThat(worker).isNotNull();
        assertThat(reaper).isNotNull();
    }

    @Test
    void pollAndProcess_happyPath_completesInvocationAndPersistsAgentReply() {
        mockLlmProvider.respondWith(invocation ->
                new LlmResponse("Hola, soy un agente", List.of(), FinishReason.STOP, LlmUsage.of(8, 4)));
        PendingInvocation enqueued = enqueueAs(clientA.id());

        worker.pollAndProcess();

        awaitInvocationStatus(enqueued.id(), PendingInvocationStatus.COMPLETED);
        Integer agentMessages = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ? AND role = 'AGENT'",
                Integer.class, conversation.id());
        assertThat(agentMessages).isEqualTo(1);
        assertThat(TenantContext.getCurrentTenantId()).isEmpty();
    }

    @Test
    void pollAndProcess_whenRetryableLlmError_releasesForRetryWithFutureNextAttempt() {
        mockLlmProvider.respondWith(invocation -> {
            throw new LlmRateLimitException("anthropic", "claude-sonnet-4-7", "429 throttled");
        });
        PendingInvocation enqueued = enqueueAs(clientA.id());

        worker.pollAndProcess();

        awaitInvocationStatus(enqueued.id(), PendingInvocationStatus.PENDING);
        Timestamp nextAttempt = jdbc.queryForObject(
                "SELECT next_attempt_at FROM pending_invocations WHERE id = ?",
                Timestamp.class, enqueued.id());
        Integer attemptCount = jdbc.queryForObject(
                "SELECT attempt_count FROM pending_invocations WHERE id = ?",
                Integer.class, enqueued.id());
        String lastError = jdbc.queryForObject(
                "SELECT last_error FROM pending_invocations WHERE id = ?",
                String.class, enqueued.id());
        assertThat(nextAttempt).isNotNull();
        assertThat(nextAttempt.toInstant()).isAfter(Instant.now());
        assertThat(attemptCount).isEqualTo(1);
        assertThat(lastError).contains("LlmRateLimitException");
    }

    @Test
    void pollAndProcess_whenNonRetryableLlmError_marksFailed() {
        mockLlmProvider.respondWith(invocation -> {
            throw new LlmAuthenticationException("anthropic", "claude-sonnet-4-7", "401 unauthorized");
        });
        PendingInvocation enqueued = enqueueAs(clientA.id());

        worker.pollAndProcess();

        awaitInvocationStatus(enqueued.id(), PendingInvocationStatus.FAILED);
        Integer agentMessages = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ? AND role = 'AGENT'",
                Integer.class, conversation.id());
        assertThat(agentMessages).isZero();
    }

    @Test
    void pollAndProcess_whenRetryableErrorAtMaxAttempts_abandons() {
        mockLlmProvider.respondWith(invocation -> {
            throw new LlmRateLimitException("anthropic", "claude-sonnet-4-7", "429 throttled");
        });
        PendingInvocation enqueued = enqueueAs(clientA.id());
        // Hand-roll the row to attemptCount = maxAttempts (3) in PENDING with no backoff,
        // simulating two prior retries already consumed.
        jdbc.update("UPDATE pending_invocations SET attempt_count = 2, next_attempt_at = NULL "
                + "WHERE id = ?", enqueued.id());

        worker.pollAndProcess();

        awaitInvocationStatus(enqueued.id(), PendingInvocationStatus.ABANDONED);
        Integer attemptCount = jdbc.queryForObject(
                "SELECT attempt_count FROM pending_invocations WHERE id = ?",
                Integer.class, enqueued.id());
        assertThat(attemptCount).isEqualTo(3);
    }

    @Test
    void pollAndProcess_whenBackoffNotCleared_doesNotPickRow() {
        mockLlmProvider.respondWith(invocation ->
                new LlmResponse("reply", List.of(), FinishReason.STOP, LlmUsage.of(1, 1)));
        PendingInvocation enqueued = enqueueAs(clientA.id());
        // Move next_attempt_at into the future: the row must NOT be claimed.
        jdbc.update("UPDATE pending_invocations SET next_attempt_at = NOW() + INTERVAL '1 hour' "
                + "WHERE id = ?", enqueued.id());

        worker.pollAndProcess();

        String status = jdbc.queryForObject(
                "SELECT status FROM pending_invocations WHERE id = ?", String.class, enqueued.id());
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void pollAndProcess_whenConversationDeleted_marksFailed() {
        mockLlmProvider.respondWith(invocation ->
                new LlmResponse("reply", List.of(), FinishReason.STOP, LlmUsage.of(1, 1)));
        PendingInvocation enqueued = enqueueAs(clientA.id());
        // Delete the messages and the conversation before the worker runs.
        jdbc.update("DELETE FROM messages WHERE conversation_id = ?", conversation.id());
        jdbc.update("DELETE FROM conversations WHERE id = ?", conversation.id());

        worker.pollAndProcess();

        awaitInvocationStatus(enqueued.id(), PendingInvocationStatus.FAILED);
    }

    @Test
    void reapOrphanedInvocations_releasesHungProcessingRow() {
        PendingInvocation enqueued = enqueueAs(clientA.id());
        // Hand-craft a PROCESSING row whose claim is well past the 1s reaper timeout.
        Instant oldClaim = Instant.now().minusSeconds(60);
        jdbc.update("UPDATE pending_invocations SET status = 'PROCESSING', "
                + "attempt_count = 1, claimed_at = ?, claimed_by = ? WHERE id = ?",
                Timestamp.from(oldClaim), "dead-worker:1:deadbeef", enqueued.id());

        reaper.reapOrphanedInvocations();

        awaitInvocationStatus(enqueued.id(), PendingInvocationStatus.PENDING);
        Timestamp nextAttempt = jdbc.queryForObject(
                "SELECT next_attempt_at FROM pending_invocations WHERE id = ?",
                Timestamp.class, enqueued.id());
        String lastError = jdbc.queryForObject(
                "SELECT last_error FROM pending_invocations WHERE id = ?",
                String.class, enqueued.id());
        assertThat(nextAttempt).isNotNull();
        assertThat(lastError).contains("reaped");
    }

    @Test
    void reapOrphanedInvocations_whenBudgetExhausted_marksAbandoned() {
        PendingInvocation enqueued = enqueueAs(clientA.id());
        Instant oldClaim = Instant.now().minusSeconds(60);
        jdbc.update("UPDATE pending_invocations SET status = 'PROCESSING', "
                + "attempt_count = 3, max_attempts = 3, claimed_at = ?, claimed_by = ? WHERE id = ?",
                Timestamp.from(oldClaim), "dead-worker:1:deadbeef", enqueued.id());

        reaper.reapOrphanedInvocations();

        awaitInvocationStatus(enqueued.id(), PendingInvocationStatus.ABANDONED);
    }

    @Test
    void pollAndProcess_processesMultipleInvocationsConcurrently() throws InterruptedException {
        int parallel = 3;
        CountDownLatch entryLatch = new CountDownLatch(parallel);
        CountDownLatch exitLatch = new CountDownLatch(1);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger currentInFlight = new AtomicInteger(0);
        mockLlmProvider.respondWith(invocation -> {
            int inFlight = currentInFlight.incrementAndGet();
            maxConcurrent.updateAndGet(prev -> Math.max(prev, inFlight));
            entryLatch.countDown();
            try {
                exitLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            currentInFlight.decrementAndGet();
            return new LlmResponse("reply", List.of(), FinishReason.STOP, LlmUsage.of(1, 1));
        });
        List<UUID> enqueuedIds = new ArrayList<>();
        TenantContext.setCurrentTenantId(clientA.id());
        try {
            for (int i = 0; i < parallel; i++) {
                Message m = messageService.appendMessage(conversation.id(), MessageRole.USER, "msg " + i);
                enqueuedIds.add(pendingInvocationService.enqueueInvocation(conversation.id(), m.id()).id());
            }
        } finally {
            TenantContext.clear();
        }

        worker.pollAndProcess();

        // All N tasks must enter the mock provider before any of them is allowed to return:
        // that is only possible if they are running concurrently.
        boolean allEntered = entryLatch.await(5, TimeUnit.SECONDS);
        assertThat(allEntered).as("all %d invocations entered the LLM concurrently", parallel).isTrue();
        assertThat(maxConcurrent.get()).isEqualTo(parallel);
        exitLatch.countDown();

        for (UUID id : enqueuedIds) {
            awaitInvocationStatus(id, PendingInvocationStatus.COMPLETED);
        }
    }

    private PendingInvocation enqueueAs(UUID context) {
        TenantContext.setCurrentTenantId(context);
        try {
            return pendingInvocationService.enqueueInvocation(conversation.id(), triggerMessage.id());
        } finally {
            TenantContext.clear();
        }
    }

    private void awaitInvocationStatus(UUID invocationId, PendingInvocationStatus expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String status = jdbc.queryForObject(
                    "SELECT status FROM pending_invocations WHERE id = ?",
                    String.class, invocationId);
            assertThat(status).isEqualTo(expected.name());
        });
    }
}
