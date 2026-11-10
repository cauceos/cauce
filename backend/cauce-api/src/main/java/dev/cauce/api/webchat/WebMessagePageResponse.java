package dev.cauce.api.webchat;

import dev.cauce.channels.webchat.WebChatMessageService;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The current thread after the cursor (ADR 0014, WC4): its id (a new one means a new thread, so the
 * widget starts over), the person's messages and the replies they got, never tool records or SYSTEM
 * text, and whether a reply is coming. {@code next_cursor} is the last message's id.
 */
public record WebMessagePageResponse(UUID conversationId, List<Item> data, UUID nextCursor, boolean awaitingReply) {

    /** One message: {@code user}, or {@code agent} for a reply, the fallback included. */
    public record Item(UUID id, String role, String text, Instant createdAt) {
    }

    static WebMessagePageResponse from(WebChatMessageService.Page page) {
        List<Item> items = page.messages().stream().map(WebMessagePageResponse::item).toList();
        return new WebMessagePageResponse(page.conversationId().orElse(null), items,
                items.isEmpty() ? null : items.get(items.size() - 1).id(), page.awaitingReply());
    }

    private static Item item(Message message) {
        return new Item(message.id(), message.role() == MessageRole.USER ? "user" : "agent", message.content(),
                message.createdAt());
    }
}
