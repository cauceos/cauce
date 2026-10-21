package dev.cauce.business.owner;

/** No owner is linked to the business, or the business is not visible. */
public class OwnerNotLinkedException extends RuntimeException {

    public OwnerNotLinkedException(String message) {
        super(message);
    }
}
