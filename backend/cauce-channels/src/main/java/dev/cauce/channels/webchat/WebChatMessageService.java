package dev.cauce.channels.webchat;

import org.springframework.beans.factory.ObjectProvider;
import java.util.stream.Collectors;
import java.util.ArrayList;
import dev.cauce.core.message.MessagePart;
import dev.cauce.core.business.catalog.CatalogSource;
import dev.cauce.core.business.catalog.CatalogItem;
import dev.cauce.core.business.catalog.Catalog;
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
    private final ObjectProvider<CatalogSource> catalogs;

    public WebChatMessageService(InboundMessageService ingest, ConversationRepository conversations,
                                 PendingInvocationService invocations, IdentityRepository identities,
                                 MessageRepository messages, MessageMapper messageMapper,
                                 ObjectProvider<CatalogSource> catalogs) {
        this.catalogs = catalogs;
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
     * Where the person was, as the shop's page reported it with the message (ADR 0014, WC8): unchecked,
     * as it came. {@code openedOnSignal} is the signal the chat opened on by itself, null when the
     * person opened it; {@code variant} the variant the browser drew.
     */
    public record PageInput(String path, String productRef, List<CartInput> cart, String openedOnSignal,
                            String variant) {
    }

    /** One cart line as the page reported it. */
    public record CartInput(String ref, Integer quantity) {
    }

    /** A message sent: the ingest's result, and whether it began a thread. */
    public record Sent(InboundMessageResult result, boolean newConversation) {
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
        return send(channel, session, content, idempotencyKey, newThread, null).result();
    }

    /**
     * {@link #send(ResolvedWebChat, WebSession, String, String, boolean)} with where the person was
     * (ADR 0014, WC8), attached to the message as its page context once checked: the path without its
     * query or fragment, and only the refs the catalog holds. A path that is not one leaves the context
     * out; it never refuses the message. The greeting the widget showed is taken from the channel's
     * configuration for the signal and variant, never from the page.
     */
    @Transactional
    public Sent send(ResolvedWebChat channel, WebSession session, String content, String idempotencyKey,
                     boolean newThread, PageInput page) {
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
        boolean newConversation = open.isEmpty() || newThread;
        if (open.isPresent() && newThread) {
            conversations.closeIfOpen(open.get()); // closed_at by the database clock (ADR 0008)
        } else if (open.isPresent()
                && messages.countByConversationIdAndRoleIn(open.get(), SHOWN) >= MAX_THREAD_MESSAGES) {
            throw new ThreadFullException("This conversation reached " + MAX_THREAD_MESSAGES
                    + " messages: start a new one to go on");
        }
        // The key is the session's: two sessions sending the same key never replay each other's message.
        String key = idempotencyKey == null ? null : "web:" + session.id() + ":" + idempotencyKey;
        List<MessagePart> parts = new ArrayList<>();
        parts.add(new MessagePart.Text(content.strip()));
        pageContext(channel, page).ifPresent(parts::add);
        return new Sent(ingest.ingestParts(channel.agentId(), ChannelConfig.WEB_CHAT, channel.channelConfigId(),
                IdentityKind.SESSION_ID, session.id().toString(), parts, key), newConversation);
    }

    /** {@code page} as a page context: checked, with the catalog's refs only; empty without a usable path. */
    Optional<MessagePart.PageContext> pageContext(ResolvedWebChat channel, PageInput page) {
        if (page == null || page.path() == null) {
            return Optional.empty();
        }
        String path = page.path().strip();
        int cut = indexOfAny(path, '?', '#');
        path = cut < 0 ? path : path.substring(0, cut);
        if (!path.startsWith("/") || path.length() > MessagePart.PageContext.MAX_PATH_LENGTH) {
            return Optional.empty();
        }
        Set<String> refs = catalogRefs(channel.tenantId());
        String productRef = page.productRef() != null && refs.contains(page.productRef()) ? page.productRef() : null;
        List<MessagePart.PageContext.CartLine> cart = new ArrayList<>();
        for (CartInput line : page.cart() == null ? List.<CartInput>of() : page.cart()) {
            int quantity = line == null || line.quantity() == null ? 1 : line.quantity();
            if (line != null && refs.contains(line.ref()) && quantity >= 1
                    && quantity <= MessagePart.PageContext.CartLine.MAX_QUANTITY
                    && cart.size() < MessagePart.PageContext.MAX_CART_LINES) {
                cart.add(new MessagePart.PageContext.CartLine(line.ref(), quantity));
            }
        }
        String greeting = channel.assistant() == null || !channel.assistant().enabled() ? null
                : channel.assistant().greetingFor(page.openedOnSignal(), page.variant()).orElse(null);
        return Optional.of(new MessagePart.PageContext(path, productRef, cart, greeting));
    }

    private Set<String> catalogRefs(UUID tenantId) {
        CatalogSource source = catalogs.getIfAvailable();
        Optional<Catalog> catalog = source == null ? Optional.empty() : source.findCatalog(tenantId);
        return catalog.map(found -> found.items().stream().map(CatalogItem::ref).collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    private static int indexOfAny(String text, char a, char b) {
        int first = text.indexOf(a);
        int second = text.indexOf(b);
        return first < 0 ? second : second < 0 ? first : Math.min(first, second);
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
