package dev.cauce.memory.identity;

import dev.cauce.core.identity.Identity;
import org.springframework.stereotype.Component;

/**
 * Hand-written bidirectional mapping between the domain {@link Identity} and its JPA
 * {@link IdentityEntity}. No external mapping library is used.
 */
@Component
public final class IdentityMapper {

    public IdentityEntity toEntity(Identity identity) {
        return new IdentityEntity(
                identity.id(),
                identity.tenantId(),
                identity.channelType(),
                identity.kind(),
                identity.value(),
                identity.createdAt());
    }

    public Identity toDomain(IdentityEntity entity) {
        return Identity.rehydrate(
                entity.getId(),
                entity.getTenantId(),
                entity.getChannelType(),
                entity.getKind(),
                entity.getValue(),
                entity.getCreatedAt());
    }
}
