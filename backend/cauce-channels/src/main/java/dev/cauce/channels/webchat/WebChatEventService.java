package dev.cauce.channels.webchat;

import dev.cauce.core.UuidGenerator;
import dev.cauce.core.business.catalog.Catalog;
import dev.cauce.core.business.catalog.CatalogItem;
import dev.cauce.core.business.catalog.CatalogSource;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the shop's page reports after the person's first message (ADR 0014, WC8): an add to cart from a
 * product card, and a purchase, attributed to the chat when it comes within 7 days of the session's last
 * message. And the anonymous opening counter, when the channel turns it on. Runs under the channel's
 * tenant context, which the caller establishes; nothing about the person is recorded.
 */
@Service
public class WebChatEventService {

    public static final int MAX_ORDER_ID_LENGTH = 100;
    /** The largest value a purchase reports: what NUMERIC(12, 2) holds. */
    public static final BigDecimal MAX_VALUE = new BigDecimal("9999999999.99");

    private final WebEventRepository events;
    private final ObjectProvider<CatalogSource> catalogs;

    public WebChatEventService(WebEventRepository events, ObjectProvider<CatalogSource> catalogs) {
        this.events = events;
        this.catalogs = catalogs;
    }

    /** A purchase reported: whether it was recorded (false when the order was reported before) and attributed. */
    public record Purchase(boolean recorded, boolean attributed) {
    }

    /**
     * Records that the person added {@code quantity} of {@code ref} to the shop's cart from a card.
     *
     * @throws ChannelDisabledException if the channel is switched off
     * @throws InvalidWebMessageException if the ref is not in the catalog or the quantity out of 1 to 99
     */
    @Transactional
    public void addToCart(ResolvedWebChat channel, WebSession session, String variant, String ref, Integer quantity) {
        channel.requireActive();
        int count = quantity == null ? 1 : quantity;
        if (count < 1 || count > 99) {
            throw new InvalidWebMessageException("quantity is between 1 and 99");
        }
        if (ref == null || !catalogRefs(channel).contains(ref)) {
            throw new InvalidWebMessageException("ref names no item of the catalog");
        }
        events.save(WebEventEntity.addToCart(UuidGenerator.newV7(), channel.tenantId(), channel.channelConfigId(),
                session.id(), variant, ref, count));
    }

    /**
     * Records the purchase {@code orderId} of {@code value} that the shop reported, once per order, and
     * attributes it when the session talked on the channel within the last 7 days.
     *
     * @throws ChannelDisabledException if the channel is switched off
     * @throws InvalidWebMessageException if the order id or the value is not one
     */
    @Transactional
    public Purchase purchase(ResolvedWebChat channel, WebSession session, String variant, String orderId,
                             BigDecimal value) {
        channel.requireActive();
        if (orderId == null || orderId.isBlank() || orderId.strip().length() > MAX_ORDER_ID_LENGTH) {
            throw new InvalidWebMessageException("order_id holds 1 to " + MAX_ORDER_ID_LENGTH + " characters");
        }
        if (value == null || value.signum() < 0 || value.compareTo(MAX_VALUE) > 0 || value.stripTrailingZeros().scale() > 2) {
            throw new InvalidWebMessageException("value is an amount from 0 with at most two decimals");
        }
        boolean attributed = events.sessionTalkedWithinAttributionWindow(session.id().toString(),
                channel.channelConfigId());
        int inserted = events.insertPurchase(UuidGenerator.newV7(), channel.tenantId(), channel.channelConfigId(),
                session.id(), variant, orderId.strip(), value, attributed);
        return new Purchase(inserted == 1, attributed);
    }

    /**
     * Counts an opening of the chat that may end without a message, when the channel's shop-assistant mode
     * turns the counter on (WC8): an aggregate per variant and day, nothing else. Whether it needs the
     * visitor's consent first is the widget's to honour, from the same configuration.
     *
     * @return whether it was counted
     * @throws InvalidWebMessageException if the kind is neither {@code proactive} nor {@code on_demand}
     */
    @Transactional
    public boolean countOpening(ResolvedWebChat channel, String variant, String kind, String signal) {
        if (channel.assistant() == null || !channel.assistant().enabled() || !channel.assistant().openingCounter()
                || !channel.active()) {
            return false;
        }
        if (!"proactive".equals(kind) && !"on_demand".equals(kind)) {
            throw new InvalidWebMessageException("kind is proactive or on_demand");
        }
        String counted = "proactive".equals(kind) && WebChatAssistant.SIGNALS.contains(signal) ? signal : "none";
        events.countOpening(channel.tenantId(), channel.channelConfigId(), variant, kind, counted);
        return true;
    }

    private Set<String> catalogRefs(ResolvedWebChat channel) {
        CatalogSource source = catalogs.getIfAvailable();
        Optional<Catalog> catalog = source == null ? Optional.empty() : source.findCatalog(channel.tenantId());
        return catalog.map(found -> found.items().stream().map(CatalogItem::ref).collect(Collectors.toSet()))
                .orElse(Set.of());
    }
}
