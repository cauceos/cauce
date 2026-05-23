package dev.cauce.core.conversation;

/**
 * Thrown when a conversation is started with a channel type that is not in the
 * set of supported channels. The supported set is owned by the application service
 * until the cauce-channels SPI provides a channel registry.
 */
public class InvalidChannelTypeException extends RuntimeException {

    public InvalidChannelTypeException(String message) {
        super(message);
    }
}
