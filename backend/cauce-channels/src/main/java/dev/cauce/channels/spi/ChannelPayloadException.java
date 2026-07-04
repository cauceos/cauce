package dev.cauce.channels.spi;

/**
 * Thrown when a provider webhook payload cannot be parsed for its channel (malformed JSON,
 * impossible shape). Distinct from an authentic-but-unsupported payload, which
 * {@link InboundChannelAdapter#parse} reports as empty instead.
 */
public class ChannelPayloadException extends RuntimeException {

    public ChannelPayloadException(String message) {
        super(message);
    }

    public ChannelPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
