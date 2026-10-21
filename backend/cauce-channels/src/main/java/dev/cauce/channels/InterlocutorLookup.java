package dev.cauce.channels;

import dev.cauce.core.identity.Identity;
import dev.cauce.memory.identity.IdentityMapper;
import dev.cauce.memory.identity.IdentityRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the interlocutor a reply goes back to: the identity a conversation references. Its own
 * transactional bean so the read runs under RLS ({@code RlsContextAspect} establishes the
 * context from {@code TenantContext}) on the outbound executor thread, where delivery
 * re-establishes the dispatching tenant. cauce-channels does not depend on cauce-tenancy, so
 * it reads through the cauce-memory repository, as {@link ChannelConfigService} does for
 * configs.
 */
@Service
public class InterlocutorLookup {

    private final IdentityRepository identityRepository;
    private final IdentityMapper identityMapper;

    public InterlocutorLookup(IdentityRepository identityRepository, IdentityMapper identityMapper) {
        this.identityRepository = identityRepository;
        this.identityMapper = identityMapper;
    }

    /** The identity, if it is visible under the current tenant context (RLS). */
    @Transactional(readOnly = true)
    public Optional<Identity> find(UUID identityId) {
        return identityRepository.findById(identityId).map(identityMapper::toDomain);
    }
}
