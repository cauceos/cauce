package dev.cauce.channels.spi;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A provider webhook request in neutral form: the HTTP headers and the raw body, exactly
 * as received. Adapters authenticate against it ({@link InboundChannelAdapter#verify}):
 * Telegram compares a header token, WhatsApp computes an HMAC signature over the raw body
 * (ADR 0006) — which is why the body travels as the received bytes, never re-encoded. A
 * signature over a decoded-and-re-encoded body can fail on any byte sequence the decoding
 * does not round-trip.
 *
 * <p>Header lookup is case-insensitive (HTTP header names are); the map is copied and
 * never mutated. The byte array is copied on the way in and on the way out.
 */
public record WebhookRequest(Map<String, String> headers, byte[] rawBody) {

    public WebhookRequest {
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(rawBody, "rawBody must not be null");
        TreeMap<String, String> caseInsensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        caseInsensitive.putAll(headers);
        headers = java.util.Collections.unmodifiableMap(caseInsensitive);
        rawBody = rawBody.clone();
    }

    /** A request whose body is the UTF-8 encoding of {@code body}. */
    public WebhookRequest(Map<String, String> headers, String body) {
        this(headers, Objects.requireNonNull(body, "body must not be null").getBytes(StandardCharsets.UTF_8));
    }

    /** The raw body exactly as received: what a signature is computed over. */
    @Override
    public byte[] rawBody() {
        return rawBody.clone();
    }

    /** The body decoded as UTF-8, for parsing. */
    public String body() {
        return new String(rawBody, StandardCharsets.UTF_8);
    }

    /** The value of {@code name} (case-insensitive), if present. */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WebhookRequest other && headers.equals(other.headers)
                && Arrays.equals(rawBody, other.rawBody);
    }

    @Override
    public int hashCode() {
        return 31 * headers.hashCode() + Arrays.hashCode(rawBody);
    }

    @Override
    public String toString() {
        // The body can carry personal data and the headers a signature: neither is printed.
        return "WebhookRequest[headers=" + headers.keySet() + ", bodyBytes=" + rawBody.length + "]";
    }
}
