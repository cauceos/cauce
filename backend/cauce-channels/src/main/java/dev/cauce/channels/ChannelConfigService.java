package dev.cauce.channels;

import dev.cauce.channels.audit.ChannelAuditEvents;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.config.ChannelConfigNotFoundException;
import dev.cauce.channels.config.ChannelConfigStatus;
import dev.cauce.channels.persistence.ChannelConfigMapper;
import dev.cauce.channels.persistence.ChannelConfigRepository;
import dev.cauce.channels.spi.ChannelAdapterRegistry;
import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.apikey.ApiKeyGenerator;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.conversation.InvalidChannelTypeException;
import dev.cauce.core.tenant.NoTenantContext;
import dev.cauce.core.tenant.TenantContext;
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
    private final AuditEventRecorder auditRecorder;

    public ChannelConfigService(ChannelConfigRepository channelConfigRepository,
                                ChannelConfigMapper channelConfigMapper,
                                AgentRepository agentRepository,
                                ChannelAdapterRegistry adapterRegistry,
                                ApiKeyHasher hasher,
                                AuditEventRecorder auditRecorder) {
        this.channelConfigRepository = channelConfigRepository;
        this.channelConfigMapper = channelConfigMapper;
        this.agentRepository = agentRepository;
        this.adapterRegistry = adapterRegistry;
        this.hasher = hasher;
        this.auditRecorder = auditRecorder;
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
        // Admin audit in the same tx, in the owning tenant's chain: ids + channel type +
        // actor only — never the provider credential, the webhook secret, or its hash.
        auditRecorder.record(ChannelAuditEvents.channelConfigured(
                saved, TenantContext.getCurrentTenantId().orElseThrow()));
        return new ChannelConfigCreationResult(saved, webhookSecret);
    }

    /**
     * Replaces the webhook secret of the config {@code configId} and returns the new one in
     * plaintext, exactly once; only its hash is stored and the old secret stops authenticating.
     * The secret is never recoverable, so this is how a provider webhook is registered again
     * (Telegram: {@code setWebhook} with the new {@code secret_token}). Recorded as
     * {@code admin.channel.webhook_secret_rotated}, ids only.
     *
     * @throws ChannelConfigNotFoundException if the config is not visible
     * @throws IllegalArgumentException if it is app-routed (a WhatsApp number has no secret of its own)
     */
    @Transactional
    public ChannelConfigCreationResult rotateWebhookSecret(UUID configId) {
        ChannelConfig current = channelConfigRepository.findById(configId).map(channelConfigMapper::toDomain)
                .orElseThrow(() -> new ChannelConfigNotFoundException("No channel config found for id " + configId));
        if (current.isAppRouted()) {
            throw new IllegalArgumentException("Channel config " + configId
                    + " receives its webhooks through its WhatsApp app and has no secret of its own");
        }
        String webhookSecret = ApiKeyGenerator.newKey();
        ChannelConfig saved = channelConfigMapper.toDomain(channelConfigRepository.save(
                channelConfigMapper.toEntity(current.withWebhookSecretHash(hasher.hash(webhookSecret)))));
        auditRecorder.record(ChannelAuditEvents.webhookSecretRotated(
                saved, TenantContext.getCurrentTenantId().orElseThrow()));
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

    /**
     * The config {@code configId} if it is visible under the current tenant context (RLS) and
     * ACTIVE. Used by outbound delivery to resolve the channel instance a conversation came in
     * through ({@code conversations.channel_config_id}, ADR 0004 PC-1), so a reply goes back
     * through the same bot even when the agent has several on the same channel. Empty when the
     * config was disabled after the thread started, or is not visible.
     */
    @Transactional(readOnly = true)
    public Optional<ChannelConfig> findActiveConfig(UUID configId) {
        return channelConfigRepository.findById(configId)
                .filter(entity -> entity.getStatus() == ChannelConfigStatus.ACTIVE)
                .map(channelConfigMapper::toDomain);
    }
}
