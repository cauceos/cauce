package dev.cauce.api.webchat;

import java.math.BigDecimal;

/**
 * Body of {@code POST /web/v1/{publicKey}/events} (ADR 0014, WC8), after the person's first message:
 * {@code add_to_cart} from a product card ({@code ref}, {@code quantity}), or {@code purchase} as the
 * shop reports it ({@code order_id}, {@code value}). {@code variant} is the one the browser drew.
 */
public record WebEventRequest(String type, String ref, Integer quantity, String orderId, BigDecimal value,
                              String variant) {

    public static final String ADD_TO_CART = "add_to_cart";
    public static final String PURCHASE = "purchase";
}
