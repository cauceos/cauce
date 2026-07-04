package dev.cauce.channels;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.persistence.ChannelConfigMapper;
import dev.cauce.channels.persistence.ChannelConfigRepository;
import dev.cauce.channels.spi.ChannelAdapterRegistry;
import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.apikey.ApiKeyGenerator;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.conversation.InvalidChannelTypeException;
import dev.cauce.core.tenant.NoTenantContext;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for channel configs: creating a binding and resolving one for the
 * webhook path. Creation is tenant-scoped ({@code RlsContextAspect} sets the RLS context
 * from {@code TenantContext}); resolution is the webhook's cross-tenant entry point and
 * goes through the V16 SECURITY DEFINER function (see
 * {@link ChannelConfigRepository#resolveActiveChannelConfig} and ADR 0001).
 */
@Service
public class ChannelConfigService {

    private final ChannelConfigRepository channelConfigRepository;
    private final ChannelConfigMapper channelConfigMapper;
    private final AgentRepository agentRepository;
    private final ChannelAdapterRegistry adapterRegistry;
    private final ApiKeyHasher hasher;

    public ChannelConfigService(ChannelConfigRepository channelConfigRepository,
                                ChannelConfigMapper channelConfigMapper,
                                AgentRepository agentRepository,
                                ChannelAdapterRegistry adapterRegistry,
                                ApiKeyHasher hasher) {
        this.channelConfigRepository = channelConfigRepository;
        this.channelConfigMapper = channelConfigMapper;
        this.agentRepository = agentRepository;
        this.adapterRegistry = adapterRegistry;
        this.hasher = hasher;
    }

    /**
     * Binds a new channel instance to {@code agentId}. The agent must be visible under the
     * current tenant context (RLS); the owning tenant recorded on the row is the
     * <em>agent's</em> tenant, not the acting context (a partner may bind a channel for
     * its client). {@code channelType} must have a registered inbound adapter — the first
     * SPI-driven validation (invariant 3).
     *
     * <p>The returned result carries the webhook secret in plaintext exactly once (mirror
     * of API-key creation); only its hash is stored. The caller passes the secret to the
     * provider (Telegram: {@code setWebhook(secret_token)}).
     *
     * @throws AgentNotFoundException if the agent does not exist or is not visible
     * @throws InvalidChannelTypeException if no adapter serves {@code channelType}
     */
    @Transactional
    public ChannelConfigCreationResult createChannelConfig(UUID agentId, String channelType,
                                                           String credential) {
        AgentEntity agent = agentRepository.findById(agentId).orElseThrow(() ->
                new AgentNotFoundException("No agent found for id " + agentId));
        if (adapterRegistry.inboundAdapter(channelType).isEmpty()) {
            throw new InvalidChannelTypeException("No channel adapter registered for type '"
                    + channelType + "'. Available: " + adapterRegistry.supportedChannelTypes());
        }
        String webhookSecret = ApiKeyGenerator.newKey();
        ChannelConfig config = ChannelConfig.create(agent.getTenantId(), agentId, channelType,
                credential, hasher.hash(webhookSecret));
        ChannelConfig saved = channelConfigMapper.toDomain(
                channelConfigRepository.save(channelConfigMapper.toEntity(config)));
        return new ChannelConfigCreationResult(saved, webhookSecret);
    }

    /**
     * Resolves the ACTIVE config {@code configId} for the webhook path, regardless of
     * tenant context — the config is what discovers the tenant. {@link NoTenantContext}:
     * the lookup goes through the V16 SECURITY DEFINER escape hatch; the caller then
     * establishes the row's tenant context and all further work runs under RLS.
     */
    @Transactional(readOnly = true)
    @NoTenantContext
    public Optional<ChannelConfig> resolveActiveChannelConfig(UUID configId) {
        return channelConfigRepository.resolveActiveChannelConfig(configId)
                .map(channelConfigMapper::toDomain);
    }
}
