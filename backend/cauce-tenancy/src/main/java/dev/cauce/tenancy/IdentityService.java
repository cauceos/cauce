package dev.cauce.tenancy;

import dev.cauce.core.identity.Identity;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.memory.identity.IdentityMapper;
import dev.cauce.memory.identity.IdentityRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for interlocutor identities (ADR 0004, decision 1). Tenant-scoped like
 * every other service: {@code RlsContextAspect} establishes the RLS context from
 * {@code TenantContext} before the transactional method runs, so the lookup is filtered and
 * the insert is checked by the identities visibility policy.
 *
 * <p>An identity is never created on its own: it is resolved as the first step of routing an
 * inbound message, inside the caller's ingest transaction, and the conversation is then
 * resolved for {@code (agent, identity)}. This service owns only the first step.
 */
@Service
public class IdentityService {

    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);

    private final IdentityRepository identityRepository;
    private final IdentityMapper identityMapper;

    public IdentityService(IdentityRepository identityRepository, IdentityMapper identityMapper) {
        this.identityRepository = identityRepository;
        this.identityMapper = identityMapper;
    }

    /**
     * Resolves the identity for the {@code (tenant, channel, kind, value)} key, creating it when
     * none exists. {@code channelType} and {@code value} are stripped by the domain factory
     * before lookup and insert, so a value with surrounding whitespace resolves to the same
     * identity as its trimmed spelling; nothing else is normalised (the core does not
     * interpret values).
     *
     * <p>{@code tenantId} is the <strong>owning</strong> tenant: the tenant of the agent the
     * interlocutor is talking to, which the caller has already read under RLS, never the acting
     * tenant of the request. A partner ingesting on behalf of its client therefore mints the
     * client's identity, and the policy admits it because the client is visible to the partner.
     * A tenant that is not visible to the current context is a programming error, not an API
     * case: the lookup misses and the insert is rejected by the RLS {@code WITH CHECK}.
     *
     * <p>Race-free create: the {@code identities_key} UNIQUE constraint (V25) is the arbiter of
     * an insert-first upsert. {@code ON CONFLICT DO NOTHING} means a concurrent first message
     * from the same interlocutor does not throw (which would poison the caller's transaction);
     * it simply skips, and the winner is re-read. Joins the caller's transaction with the
     * default {@code REQUIRED} propagation.
     *
     * @throws dev.cauce.core.conversation.InvalidChannelTypeException if {@code channelType} is
     *     not supported
     * @throws IllegalArgumentException if {@code channelType} or {@code value} is blank
     * @throws NullPointerException if {@code tenantId} or {@code kind} is null
     */
    @Transactional
    public Identity resolveOrCreate(UUID tenantId, String channelType, IdentityKind kind, String value) {
        Identity candidate = Identity.create(tenantId, channelType, kind, value);
        SupportedChannels.requireSupported(candidate.channelType());

        Optional<Identity> existing = find(candidate);
        if (existing.isPresent()) {
            return existing.get();
        }
        int inserted = identityRepository.insertIfAbsent(candidate.id(), candidate.tenantId(),
                candidate.channelType(), candidate.kind().name(), candidate.value());
        if (inserted == 0) {
            log.debug("Concurrent identity for tenant {} on channel {} ({}); reusing the existing row",
                    candidate.tenantId(), candidate.channelType(), candidate.kind());
        }
        return find(candidate).orElseThrow(() -> new IllegalStateException(
                "Expected an identity after insert-or-skip for tenant " + candidate.tenantId()
                        + " on channel " + candidate.channelType()));
    }

    private Optional<Identity> find(Identity key) {
        return identityRepository
                .findByTenantIdAndChannelTypeAndKindAndValue(
                        key.tenantId(), key.channelType(), key.kind(), key.value())
                .map(identityMapper::toDomain);
    }
}
