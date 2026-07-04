package dev.cauce.channels.spi;

/**
 * Thrown when an outbound delivery fails: the provider rejected the message or the call
 * itself failed. Retry/backoff policy belongs to the caller (the outbound commit), not to
 * the adapter.
 */
public class ChannelDeliveryException extends RuntimeException {

    public ChannelDeliveryException(String message) {
        super(message);
    }

    public ChannelDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
