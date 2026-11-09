package dev.cauce.core.business.handoff;

import dev.cauce.core.business.booking.Booking;
import java.util.List;
import java.util.UUID;

/**
 * Port: where an agent hands a case to the owner (ADR 0007, B7). The native adapter records the
 * handoff and queues the owner's notice in the same transaction; a helpdesk or a CRM would be
 * another adapter. The conversation is never moved out of OPEN.
 */
public interface HandoffSink {

    /**
     * Opens a handoff for the conversation of {@code requester} and queues the owner's notice.
     *
     * @throws InvalidHandoffCategoryException if the business lists its categories and this is not one
     * @throws UnquotedCustomerMessageException if the request's customer message is not the words of
     *         the person in this conversation
     */
    HandoffReceipt open(Booking.Requester requester, Handoff.Request request);

    /**
     * The OPEN handoffs of the person {@code identityId} in the business {@code tenantId}, oldest
     * first, whichever of their conversations each came from: what the person can ask about after a
     * new thread (ADR 0013, T1). Resolved ones are left out.
     */
    List<Handoff> openForIdentity(UUID tenantId, UUID identityId);

    /** The opened handoff, and whether the owner's notice was queued (an owner is linked). */
    record HandoffReceipt(Handoff handoff, boolean ownerNotified) {
    }
}
