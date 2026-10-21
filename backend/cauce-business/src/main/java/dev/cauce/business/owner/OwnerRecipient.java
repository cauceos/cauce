package dev.cauce.business.owner;

import dev.cauce.core.business.owner.OwnerNotifier;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The owner of a business, as the channel layer reaches them (ADR 0007, B8): their identity on the
 * owner bot, the bot's channel config, and when they linked. One per business; linking again
 * replaces it.
 */
public record OwnerRecipient(UUID id, UUID tenantId, UUID channelConfigId, UUID identityId, Instant linkedAt) {

    public OwnerRecipient {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(channelConfigId, "channelConfigId");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(linkedAt, "linkedAt");
    }

    /** Where the notifier sends to. */
    public OwnerNotifier.Recipient toRecipient() {
        return new OwnerNotifier.Recipient(channelConfigId, identityId, linkedAt);
    }
}
