package dev.cauce.channels.webchat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA mapping of {@code web_events} (V66): an add to cart from a product card, or a purchase the shop
 * reported (ADR 0014, WC8). RLS-filtered by its tenant. Its creation time is the database's default.
 */
@Entity
@Table(name = "web_events")
public class WebEventEntity {

    public static final String ADD_TO_CART = "ADD_TO_CART";
    public static final String PURCHASE = "PURCHASE";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "channel_config_id", nullable = false, updatable = false)
    private UUID channelConfigId;

    @Column(name = "session_id", updatable = false)
    private UUID sessionId;

    @Column(name = "kind", nullable = false, updatable = false, length = 20)
    private String kind;

    @Column(name = "variant", nullable = false, updatable = false, length = 20)
    private String variant;

    @Column(name = "ref", updatable = false, length = 64)
    private String ref;

    @Column(name = "quantity", updatable = false)
    private Integer quantity;

    @Column(name = "order_id", updatable = false, length = 100)
    private String orderId;

    @Column(name = "value", updatable = false)
    private BigDecimal value;

    @Column(name = "attributed", nullable = false, updatable = false)
    private boolean attributed;

    protected WebEventEntity() {
        // for JPA
    }

    /** An add to cart of {@code quantity} of {@code ref} from a card. */
    public static WebEventEntity addToCart(UUID id, UUID tenantId, UUID channelConfigId, UUID sessionId, String variant,
                                           String ref, int quantity) {
        WebEventEntity event = new WebEventEntity();
        event.id = id;
        event.tenantId = tenantId;
        event.channelConfigId = channelConfigId;
        event.sessionId = sessionId;
        event.kind = ADD_TO_CART;
        event.variant = variant;
        event.ref = ref;
        event.quantity = quantity;
        return event;
    }

    public UUID getId() {
        return id;
    }

    public String getKind() {
        return kind;
    }

    public String getRef() {
        return ref;
    }

    public boolean isAttributed() {
        return attributed;
    }
}
