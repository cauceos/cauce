package dev.cauce.channels.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A provider webhook request in neutral form: the HTTP headers and the raw body, exactly
 * as received. Adapters authenticate against it ({@link InboundChannelAdapter#verify}):
 * Telegram compares a header token, WhatsApp computes an HMAC signature over the raw body
 * — which is why the body travels unparsed here.
 *
 * <p>Header lookup is case-insensitive (HTTP header names are); the map is copied and
 * never mutated.
 */
public record WebhookRequest(Map<String, String> headers, String body) {

    public WebhookRequest {
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(body, "body must not be null");
        TreeMap<String, String> caseInsensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        caseInsensitive.putAll(headers);
        headers = java.util.Collections.unmodifiableMap(caseInsensitive);
    }

    /** The value of {@code name} (case-insensitive), if present. */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name));
    }
}
