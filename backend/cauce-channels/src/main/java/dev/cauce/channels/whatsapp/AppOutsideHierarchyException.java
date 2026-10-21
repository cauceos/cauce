package dev.cauce.channels.whatsapp;

/**
 * A number may be bound only to a WhatsApp app owned by the number's tenant or an ancestor of it
 * (ADR 0006, W2): a partner's app serves its clients, not a sibling's.
 */
public class AppOutsideHierarchyException extends RuntimeException {

    public AppOutsideHierarchyException(String message) {
        super(message);
    }
}
