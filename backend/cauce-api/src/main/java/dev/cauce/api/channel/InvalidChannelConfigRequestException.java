package dev.cauce.api.channel;

/** A channel binding request lacks what its channel needs (a WhatsApp number without its app or id). */
public class InvalidChannelConfigRequestException extends RuntimeException {

    public InvalidChannelConfigRequestException(String message) {
        super(message);
    }
}
