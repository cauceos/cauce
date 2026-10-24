package dev.cauce.channels.spi;

/**
 * A provider did not hand over a voice note's bytes: an HTTP error, a reference it no longer
 * knows, or a body that is not what it announced. Never carries the credential.
 */
public class ChannelMediaException extends RuntimeException {

    public ChannelMediaException(String message) {
        super(message);
    }

    public ChannelMediaException(String message, Throwable cause) {
        super(message, cause);
    }
}
