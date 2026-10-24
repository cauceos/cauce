package dev.cauce.channels.spi;

/**
 * A provider did not hand over a voice note's bytes: an HTTP error, a reference it no longer
 * knows, or a body that is not what it announced. Never carries the credential. Transient when a
 * later attempt may succeed: the provider unreachable, or answering 5xx or 429.
 */
public class ChannelMediaException extends RuntimeException {

    private final boolean transientFailure;

    /** A permanent failure. */
    public ChannelMediaException(String message) {
        this(message, false, null);
    }

    /** A transient failure: the call itself failed. */
    public ChannelMediaException(String message, Throwable cause) {
        this(message, true, cause);
    }

    public ChannelMediaException(String message, boolean transientFailure, Throwable cause) {
        super(message, cause);
        this.transientFailure = transientFailure;
    }

    /** A failure of an HTTP {@code status}: transient for 5xx and 429. */
    public static ChannelMediaException ofStatus(int status, String message) {
        return new ChannelMediaException(message, status >= 500 || status == 429, null);
    }

    public boolean isTransient() {
        return transientFailure;
    }
}
