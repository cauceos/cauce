package dev.cauce.api.webchat;

import dev.cauce.channels.ProductCards;
import dev.cauce.channels.webchat.WebChatMessageService;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The current thread after the cursor (ADR 0014, WC4): its id (a new one means a new thread, so the
 * widget starts over), the person's messages and the replies they got, never tool records or SYSTEM
 * text, and whether a reply is coming. {@code next_cursor} is the last message's id.
 */
public record WebMessagePageResponse(UUID conversationId, List<Item> data, UUID nextCursor, boolean awaitingReply) {

    /**
     * One message: {@code user}, or {@code agent} for a reply, the fallback included. A reply's
     * {@code cards} are its product cards as the catalog fills them in now (ADR 0014, WC8), in order,
     * without the refs it no longer holds; empty for every other message.
     */
    public record Item(UUID id, String role, String text, Instant createdAt, List<Card> cards) {
    }

    /** A product card: what the widget shows, and the ref its button hands to the shop's hook. */
    public record Card(String ref, String name, String description, BigDecimal price) {
    }

    static WebMessagePageResponse from(WebChatMessageService.Page page,
                                       Function<List<String>, List<ProductCards.Card>> cards) {
        List<Item> items = page.messages().stream().map(message -> item(message, cards)).toList();
        return new WebMessagePageResponse(page.conversationId().orElse(null), items,
                items.isEmpty() ? null : items.get(items.size() - 1).id(), page.awaitingReply());
    }

    private static Item item(Message message, Function<List<String>, List<ProductCards.Card>> cards) {
        List<Card> shown = message.productCards().isEmpty() ? List.of() : cards.apply(message.productCards()).stream()
                .map(card -> new Card(card.ref(), card.name(), card.description(), card.price())).toList();
        return new Item(message.id(), message.role() == MessageRole.USER ? "user" : "agent", message.content(),
                message.createdAt(), shown);
    }
}
