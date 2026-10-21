package dev.cauce.channels.spi;

/**
 * A delivery the channel refuses because the interlocutor's reply window is closed (WhatsApp's 24
 * hours, ADR 0006, W4) or cannot be checked. Not a failure a retry would fix: a proactive sender
 * records it as not sent (ADR 0007, B9) instead of trying again.
 */
public class OutsideReplyWindowException extends ChannelDeliveryException {

    public OutsideReplyWindowException(String message) {
        super(message);
    }
}
