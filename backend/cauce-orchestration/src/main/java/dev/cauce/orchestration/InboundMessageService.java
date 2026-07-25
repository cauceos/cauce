package dev.cauce.orchestration;

import dev.cauce.core.UuidGenerator;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.orchestration.audit.ConductAuditEvents;
import dev.cauce.orchestration.events.InvocationRequested;
import dev.cauce.orchestration.exception.InvalidIdempotencyKeyException;
import dev.cauce.orchestration.persistence.IngestIdempotencyRecordMapper;
import dev.cauce.orchestration.persistence.IngestIdempotencyRecordRepository;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.MessageService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The inbound ingest unit: the single, channel-agnostic entry point that turns an incoming
 * USER message into queued work for the asynchronous orchestrator. The REST messaging endpoint
 * and future cauce-channels adapters all call {@link #ingest}; an adapter passes its own
 * {@code channelType} (e.g. {@code "whatsapp"}), the REST endpoint passes the reserved
 * built-in {@code "api"}.
 *
 * <p>{@link #ingest} runs in one transaction. It calls three tenant-scoped services
 * ({@link ConversationService#resolveOrStartConversation}, {@link MessageService#appendMessage},
 * {@link PendingInvocationService#enqueueInvocation}); with the default {@code REQUIRED}
 * propagation they join this transaction, so resolve / append / enqueue commit or roll back
 * together. {@code RlsContextAspect} sets the tenant RLS context (from {@code TenantContext})
 * inside the transaction, exactly as for any single service call; the caller must have
 * established that context (the API derives it from the validated API key).
 *
 * <p><strong>Idempotency.</strong> A caller may pass an opaque idempotency key (the REST
 * endpoint forwards the optional {@code Idempotency-Key} header; a channel adapter would pass
 * the provider's message id). A repeated ingest with the same {@code (agentId, key)} returns
 * the stored result of the original — same conversation, message and invocation ids — and
 * performs no work: no second USER message, no second invocation, and no second
 * {@link InvocationRequested} event. Deduplication is by key match only; the request body is
 * not fingerprinted (the threat model is byte-identical redelivery, not a caller reusing a
 * key with a different body). Without a key, behavior is exactly the pre-idempotency ingest.
 *
 * <p>The scheme is insert-first within the single ingest transaction: after resolving the
 * conversation (which keeps agent/channel validation and its error shapes intact), the key is
 * claimed with {@code INSERT ... ON CONFLICT DO NOTHING} on the V15 unique constraint and the
 * row is completed with the result ids before commit. A concurrent duplicate blocks on the
 * unique index until the original commits, then reads the winner's complete row — so it never
 * duplicates work, and the transient incomplete row is never observable outside its own
 * transaction.
 */
@Service
public class InboundMessageService {

    /** Must match the {@code idempotency_key VARCHAR(255)} column (V15). */
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 255;

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final PendingInvocationService pendingInvocationService;
    private final IngestIdempotencyRecordRepository idempotencyRecordRepository;
    private final IngestIdempotencyRecordMapper idempotencyRecordMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditEventRecorder auditRecorder;

    public InboundMessageService(ConversationService conversationService,
                                 MessageService messageService,
                                 PendingInvocationService pendingInvocationService,
                                 IngestIdempotencyRecordRepository idempotencyRecordRepository,
                                 IngestIdempotencyRecordMapper idempotencyRecordMapper,
                                 ApplicationEventPublisher eventPublisher,
                                 AuditEventRecorder auditRecorder) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.pendingInvocationService = pendingInvocationService;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.idempotencyRecordMapper = idempotencyRecordMapper;
        this.eventPublisher = eventPublisher;
        this.auditRecorder = auditRecorder;
    }

    /**
     * Ingests an inbound USER message without deduplication; equivalent to
     * {@link #ingest(UUID, String, String, String, String)} with a {@code null} key.
     */
    @Transactional
    public InboundMessageResult ingest(UUID agentId, String channelType, String externalIdentityRef,
                                       String content) {
        return ingest(agentId, channelType, externalIdentityRef, content, null);
    }

    /**
     * Ingests an inbound USER message for {@code agentId} from the external user
     * {@code externalIdentityRef} on {@code channelType}: resolves (or starts) the conversation,
     * appends the USER message, and enqueues the invocation that will produce the agent's reply.
     *
     * <p>With a non-null {@code idempotencyKey}, a request whose {@code (agentId, key)} was
     * already accepted returns the original result and performs no work (see the class javadoc);
     * a {@code null} key disables deduplication.
     *
     * @throws dev.cauce.core.agent.AgentNotFoundException if the agent is not visible under the
     *     current tenant context (RLS)
     * @throws dev.cauce.core.conversation.InvalidChannelTypeException if {@code channelType} is
     *     not supported
     * @throws InvalidIdempotencyKeyException if {@code idempotencyKey} is present but blank or
     *     longer than {@value #IDEMPOTENCY_KEY_MAX_LENGTH} characters
     */
    @Transactional
    public InboundMessageResult ingest(UUID agentId, String channelType, String externalIdentityRef,
                                       String content, String idempotencyKey) {
        if (idempotencyKey != null) {
            requireValidKey(idempotencyKey);
            // Fast-path replay of a committed ingest. Deliberately BEFORE resolving the
            // conversation: a late replay must not open a fresh conversation if the original
            // one has been closed since.
            Optional<InboundMessageResult> replayed = findRecordedResult(agentId, idempotencyKey);
            if (replayed.isPresent()) {
                return replayed.get();
            }
        }

        Conversation conversation =
                conversationService.resolveOrStartConversation(agentId, channelType, externalIdentityRef);

        UUID lockId = null;
        if (idempotencyKey != null) {
            // Claim the key. The lock goes AFTER resolveOrStartConversation so agent/channel
            // validation keeps its error shapes (an insert for an invisible agent would fail
            // the RLS WITH CHECK instead of raising AgentNotFoundException). If a concurrent
            // duplicate holds the key, this insert blocks on the V15 unique index until that
            // transaction commits; losing the race means the winner's committed row is
            // complete, so we return its result and write nothing. (Known benign edge: if the
            // race overlaps a third-party close of the conversation, the loser may commit the
            // fresh OPEN conversation resolved above — the same thread the identity's next
            // message would open anyway.)
            lockId = UuidGenerator.newV7();
            int inserted = idempotencyRecordRepository.insertLockIfAbsent(
                    lockId, agentId, idempotencyKey);
            if (inserted == 0) {
                return findRecordedResult(agentId, idempotencyKey).orElseThrow(() ->
                        new IllegalStateException("Idempotency key for agent " + agentId
                                + " is taken but its record is not visible"));
            }
        }

        Message userMessage = messageService.appendMessage(conversation.id(), MessageRole.USER, content);
        PendingInvocation invocation =
                pendingInvocationService.enqueueInvocation(conversation.id(), userMessage.id());
        if (idempotencyKey != null) {
            idempotencyRecordRepository.recordResult(
                    lockId, conversation.id(), userMessage.id(), invocation.id());
        }
        // Audit capture (conduct.message.received) joins THIS transaction: if the ingest
        // rolls back, no audit record survives — absence of the fact means absence of the
        // record. Only the winning path records: an idempotent replay is audit-silent, like
        // it is event-silent.
        auditRecorder.record(ConductAuditEvents.messageReceived(invocation.tenantId(),
                invocation.id(), agentId, channelType, userMessage));
        // Published synchronously INSIDE the ingest transaction: if it rolls back, the event
        // will have fired for work that never existed. Harmless with no consumers; TODO when a
        // persisting consumer lands, it must listen with
        // @TransactionalEventListener(phase = AFTER_COMMIT) (or move this behind an outbox).
        // Only this winning path publishes: an idempotent replay is event-silent.
        eventPublisher.publishEvent(new InvocationRequested(invocation.id(), invocation.tenantId(),
                agentId, conversation.id(), userMessage.id(), Instant.now()));
        return new InboundMessageResult(conversation.id(), userMessage.id(), invocation.id());
    }

    private Optional<InboundMessageResult> findRecordedResult(UUID agentId, String idempotencyKey) {
        return idempotencyRecordRepository.findByAgentIdAndIdempotencyKey(agentId, idempotencyKey)
                .map(idempotencyRecordMapper::toDomain)
                .map(IngestIdempotencyRecord::result);
    }

    private static void requireValidKey(String idempotencyKey) {
        if (idempotencyKey.isBlank()) {
            throw new InvalidIdempotencyKeyException("Idempotency key must not be blank");
        }
        if (idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new InvalidIdempotencyKeyException("Idempotency key must not exceed "
                    + IDEMPOTENCY_KEY_MAX_LENGTH + " characters");
        }
    }
}
