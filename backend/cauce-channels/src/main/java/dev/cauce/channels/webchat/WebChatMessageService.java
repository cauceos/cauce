package dev.cauce.channels.webchat;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.core.conversation.ConversationStatus;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.memory.conversation.ConversationEntity;
import dev.cauce.memory.conversation.ConversationRepository;
import dev.cauce.memory.identity.IdentityEntity;
import dev.cauce.memory.identity.IdentityRepository;
import dev.cauce.memory.message.MessageEntity;
import dev.cauce.memory.message.MessageMapper;
import dev.cauce.memory.message.MessageRepository;
import dev.cauce.orchestration.InboundMessageResult;
import dev.cauce.orchestration.InboundMessageService;
import dev.cauce.orchestration.PendingInvocationService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A web chat's messages (ADR 0014, W3): what a session sends goes through the ingest every channel uses
 * (ADR 0004), as the person {@code SESSION_ID} = the session's id on channel {@code web_chat}; what it
 * reads is its current thread, polled after a cursor. Runs under the session's tenant context, which
 * the caller establishes after authenticating the session's token.
 *
 * <p>Bounds (WC3): a message holds at most {@value #MAX_CONTENT_LENGTH} characters, and a thread at most
 * {@value #MAX_THREAD_MESSAGES} conversational messages; past it the person starts again
 * ({@code newThread}), and their session goes on.
 */
@Service
public class WebChatMessageService {

    public static final int MAX_CONTENT_LENGTH = 2_000;
    public static final int MAX_THREAD_MESSAGES = 200;
    public static final int DEFAULT_PAGE = 50;
    public static final int MAX_PAGE = 100;
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

    /** What the person sees of a thread: their words and the replies they got (WC4). */
    static final Set<MessageRole> SHOWN = Set.of(MessageRole.USER, MessageRole.AGENT, MessageRole.FALLBACK);

    private final InboundMessageService ingest;
    private final ConversationRepository conversations;
    private final PendingInvocationService invocations;
    private final IdentityRepository identities;
    private final MessageRepository messages;
    private final MessageMapper messageMapper;

    public WebChatMessageService(InboundMessageService ingest, ConversationRepository conversations,
                                 PendingInvocationService invocations, IdentityRepository identities,
                                 MessageRepository messages, MessageMapper messageMapper) {
        this.ingest = ingest;
        this.conversations = conversations;
        this.invocations = invocations;
        this.identities = identities;
        this.messages = messages;
        this.messageMapper = messageMapper;
    }

    /** One page of the current thread: its id, the messages after the cursor, and whether a reply is coming. */
    public record Page(Optional<UUID> conversationId, List<Message> messages, boolean awaitingReply) {
    }

    /**
     * Sends {@code content} from {@code session} to the channel's agent. With {@code newThread}, the
     * person's open thread is closed first, so the message opens a new one.
     *
     * @throws ChannelDisabledException if the channel is switched off
     * @throws InvalidWebMessageException if the content is blank or too long, or the key too long
     * @throws ThreadFullException if the open thread holds {@value #MAX_THREAD_MESSAGES} messages already
     */
    @Transactional
    public InboundMessageResult send(ResolvedWebChat channel, WebSession session, String content,
                                     String idempotencyKey, boolean newThread) {
        channel.requireActive();
        if (content == null || content.isBlank()) {
            throw new InvalidWebMessageException("content must not be blank");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new InvalidWebMessageException("content holds at most " + MAX_CONTENT_LENGTH + " characters");
        }
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH)) {
            throw new InvalidWebMessageException("Idempotency-Key holds 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        Optional<UUID> open = openThread(channel, session);
        if (open.isPresent() && newThread) {
            conversations.closeIfOpen(open.get()); // closed_at by the database clock (ADR 0008)
        } else if (open.isPresent()
                && messages.countByConversationIdAndRoleIn(open.get(), SHOWN) >= MAX_THREAD_MESSAGES) {
            throw new ThreadFullException("This conversation reached " + MAX_THREAD_MESSAGES
                    + " messages: start a new one to go on");
        }
        // The key is the session's: two sessions sending the same key never replay each other's message.
        String key = idempotencyKey == null ? null : "web:" + session.id() + ":" + idempotencyKey;
        return ingest.ingest(channel.agentId(), ChannelConfig.WEB_CHAT, channel.channelConfigId(),
                IdentityKind.SESSION_ID, session.id().toString(), content.strip(), key);
    }

    /**
     * The current thread of {@code session}, after the message {@code cursor} (all of it without one,
     * or when the cursor belongs to an earlier thread). An unknown cursor is an error; a cursor of
     * someone else's thread reads as an earlier thread of this one, and shows only this thread.
     *
     * @throws InvalidWebMessageException if the cursor names no visible message
     */
    @Transactional(readOnly = true)
    public Page read(ResolvedWebChat channel, WebSession session, UUID cursor, Integer limit) {
        int rows = limit == null ? DEFAULT_PAGE : Math.max(1, Math.min(MAX_PAGE, limit));
        Optional<UUID> open = openThread(channel, session);
        if (open.isEmpty()) {
            return new Page(Optional.empty(), List.of(), false);
        }
        UUID conversationId = open.get();
        long afterSeq = 0;
        if (cursor != null) {
            MessageEntity at = messages.findById(cursor)
                    .orElseThrow(() -> new InvalidWebMessageException("cursor names no message"));
            afterSeq = at.getConversationId().equals(conversationId) ? at.getSeq() : 0;
        }
        List<Message> page = messages.findByConversationIdAndRoleInAndSeqGreaterThanOrderBySeqAsc(
                conversationId, SHOWN, afterSeq, Limit.of(rows)).stream().map(messageMapper::toDomain).toList();
        return new Page(Optional.of(conversationId), page, invocations.hasInvocationInFlight(conversationId));
    }

    /** The session's OPEN thread with the channel's agent, under RLS, if it has one. */
    private Optional<UUID> openThread(ResolvedWebChat channel, WebSession session) {
        Optional<IdentityEntity> identity = identities.findByTenantIdAndChannelTypeAndKindAndValue(
                channel.tenantId(), ChannelConfig.WEB_CHAT, IdentityKind.SESSION_ID, session.id().toString());
        return identity.flatMap(found -> conversations.findByAgentIdAndIdentityIdAndChannelConfigIdAndStatus(
                channel.agentId(), found.getId(), channel.channelConfigId(), ConversationStatus.OPEN))
                .map(ConversationEntity::getId);
    }
}
