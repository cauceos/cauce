package dev.cauce.api.business;

import dev.cauce.business.owner.OwnerRecipient;
import java.time.Instant;
import java.util.UUID;

/** Where the owner of a business reads its notices: their chat on the owner bot. */
public record OwnerResponse(UUID tenantId, UUID channelConfigId, UUID identityId, Instant linkedAt) {

    public static OwnerResponse from(OwnerRecipient owner) {
        return new OwnerResponse(owner.tenantId(), owner.channelConfigId(), owner.identityId(), owner.linkedAt());
    }
}
