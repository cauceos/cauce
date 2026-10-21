package dev.cauce.core.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A new interlocutor identity was minted (the insert-first resolve-or-create won its race). A
 * resolve that found an existing identity, or lost the race to a concurrent creator, publishes
 * nothing. Carries no {@code value}: see {@link IdentityEvent}.
 */
public record IdentityCreated(UUID identityId, UUID tenantId, String channelType, IdentityKind kind,
                              Instant occurredAt) implements IdentityEvent {

    public static final String TYPE = "conduct.identity.created";

    public IdentityCreated {
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(channelType, "channelType");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    @Override
    public String type() {
        return TYPE;
    }
}
