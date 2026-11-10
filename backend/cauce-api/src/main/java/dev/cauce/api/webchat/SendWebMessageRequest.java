package dev.cauce.api.webchat;

import java.util.List;

/**
 * Body of {@code POST /web/v1/{publicKey}/messages} (ADR 0014, W3): what the person wrote and, to start
 * over, {@code new_thread}. Bounds are checked by the service, so the widget gets one error shape.
 *
 * <p>With the shop-assistant mode (WC8), {@code context} is where the person was, as the shop's page
 * reported it: the path, the item on screen and the cart, by catalog ref, never free text from the page.
 * {@code opening} says how the chat was opened before this message ({@code proactive} on a signal, or
 * {@code on_demand}) and the variant the browser drew; it is counted, not stored.
 */
public record SendWebMessageRequest(String content, Boolean newThread, Context context, Opening opening) {

    /** Where the person was. */
    public record Context(String path, String productRef, List<CartLine> cart) {
    }

    /** One cart line: a catalog ref and how many. */
    public record CartLine(String ref, Integer quantity) {
    }

    /** How the chat was opened, and the variant. */
    public record Opening(String kind, String signal, String variant) {

        public static final String PROACTIVE = "proactive";
        public static final String ON_DEMAND = "on_demand";
    }
}
