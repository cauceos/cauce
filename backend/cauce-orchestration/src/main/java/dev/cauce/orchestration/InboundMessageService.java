package dev.cauce.orchestration;

import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.orchestration.events.InvocationRequested;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.MessageService;
import java.time.Instant;
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
 */
@Service
public class InboundMessageService {

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final PendingInvocationService pendingInvocationService;
    private final ApplicationEventPublisher eventPublisher;

    public InboundMessageService(ConversationService conversationService,
                                 MessageService messageService,
                                 PendingInvocationService pendingInvocationService,
                                 ApplicationEventPublisher eventPublisher) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.pendingInvocationService = pendingInvocationService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Ingests an inbound USER message for {@code agentId} from the external user
     * {@code externalIdentityRef} on {@code channelType}: resolves (or starts) the conversation,
     * appends the USER message, and enqueues the invocation that will produce the agent's reply.
     *
     * @throws dev.cauce.core.agent.AgentNotFoundException if the agent is not visible under the
     *     current tenant context (RLS)
     * @throws dev.cauce.core.conversation.InvalidChannelTypeException if {@code channelType} is
     *     not supported
     */
    @Transactional
    public InboundMessageResult ingest(UUID agentId, String channelType, String externalIdentityRef,
                                       String content) {
        Conversation conversation =
                conversationService.resolveOrStartConversation(agentId, channelType, externalIdentityRef);
        Message userMessage = messageService.appendMessage(conversation.id(), MessageRole.USER, content);
        PendingInvocation invocation =
                pendingInvocationService.enqueueInvocation(conversation.id(), userMessage.id());
        // Published synchronously INSIDE the ingest transaction: if it rolls back, the event
        // will have fired for work that never existed. Harmless with no consumers; TODO when a
        // persisting consumer lands, it must listen with
        // @TransactionalEventListener(phase = AFTER_COMMIT) (or move this behind an outbox).
        eventPublisher.publishEvent(new InvocationRequested(invocation.id(), invocation.tenantId(),
                agentId, conversation.id(), userMessage.id(), Instant.now()));
        return new InboundMessageResult(conversation.id(), userMessage.id(), invocation.id());
    }
}
