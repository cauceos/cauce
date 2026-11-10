package dev.cauce.api.webchat;

/**
 * Body of {@code POST /web/v1/{publicKey}/openings} (ADR 0014, WC8): an opening of the chat for the
 * anonymous counter, with no session and no identifier, only how it opened and the variant.
 */
public record OpeningRequest(String kind, String signal, String variant) {
}
