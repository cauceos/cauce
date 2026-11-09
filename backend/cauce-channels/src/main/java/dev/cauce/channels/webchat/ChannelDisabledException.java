package dev.cauce.channels.webchat;

/**
 * A web chat switched off by its owner (the emergency switch, ADR 0014): it takes no new sessions or
 * messages until it is enabled again. The widget gets this as {@code channel_disabled}.
 */
public class ChannelDisabledException extends RuntimeException {

    public ChannelDisabledException(String message) {
        super(message);
    }
}
