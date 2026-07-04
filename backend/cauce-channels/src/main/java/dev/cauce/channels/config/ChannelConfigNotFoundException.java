package dev.cauce.channels.config;

/**
 * Thrown when a channel config does not exist, is not ACTIVE, or is not visible under the
 * current tenant context. The cases are deliberately indistinguishable so an
 * unauthenticated webhook probe cannot learn which config ids exist.
 */
public class ChannelConfigNotFoundException extends RuntimeException {

    public ChannelConfigNotFoundException(String message) {
        super(message);
    }
}
