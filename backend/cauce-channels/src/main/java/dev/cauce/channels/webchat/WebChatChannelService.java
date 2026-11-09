package dev.cauce.channels.webchat;

import dev.cauce.channels.audit.ChannelAuditEvents;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.persistence.ChannelConfigMapper;
import dev.cauce.channels.persistence.ChannelConfigRepository;
import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.tenant.NoTenantContext;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.usage.SpendLimitRequiredException;
import dev.cauce.core.usage.SpendLimitRequirement;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.orchestration.usage.SpendLimitService;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Web-chat channels (ADR 0014, W1): creating one for an agent and resolving one by its public key for
 * the public surface. A tenant with an active web chat must have a spend limit (WC3): creation is
 * refused without one, and this service is the {@link SpendLimitRequirement} that stops the limit
 * from being removed while the channel is active.
 */
@Service
public class WebChatChannelService implements SpendLimitRequirement {

    private final ChannelConfigRepository configRepository;
    private final ChannelConfigMapper configMapper;
    private final WebChatSettingsRepository settingsRepository;
    private final AgentRepository agentRepository;
    private final SpendLimitService spendLimits;
    private final AuditEventRecorder auditRecorder;

    public WebChatChannelService(ChannelConfigRepository configRepository, ChannelConfigMapper configMapper,
                                 WebChatSettingsRepository settingsRepository, AgentRepository agentRepository,
                                 SpendLimitService spendLimits, AuditEventRecorder auditRecorder) {
        this.configRepository = configRepository;
        this.configMapper = configMapper;
        this.settingsRepository = settingsRepository;
        this.agentRepository = agentRepository;
        this.spendLimits = spendLimits;
        this.auditRecorder = auditRecorder;
    }

    /** What a web chat is created with; the Turnstile secret is sealed at rest, never returned. */
    public record Creation(String turnstileSecret, String turnstileSiteKey, List<String> allowedOrigins,
                           String privacyPolicyUrl, ConsentMode consentMode) {
    }

    /**
     * Creates a web chat for {@code agentId}, owned by the agent's tenant, with a fresh public key.
     *
     * @throws AgentNotFoundException if the agent is not visible
     * @throws SpendLimitRequiredException if the agent's tenant has no spend limit
     * @throws IllegalArgumentException if a setting is malformed
     */
    @Transactional
    public WebChatChannel create(UUID agentId, Creation creation) {
        AgentEntity agent = agentRepository.findById(agentId).orElseThrow(() ->
                new AgentNotFoundException("No agent found for id " + agentId));
        if (creation.turnstileSecret() == null || creation.turnstileSecret().isBlank()) {
            throw new IllegalArgumentException("turnstile_secret must not be blank");
        }
        if (spendLimits.status(agent.getTenantId()).monthlyLimitUsd() == null) {
            throw new SpendLimitRequiredException("A web chat is open to anonymous traffic: tenant "
                    + agent.getTenantId() + " needs a spend limit, set by its partner or operator, first");
        }
        String publicKey = PublicKeys.newKey();
        while (settingsRepository.resolveByPublicKey(publicKey).isPresent()) {
            publicKey = PublicKeys.newKey(); // the rare collision: a fresh key
        }
        ChannelConfig config = ChannelConfig.createWebChat(agent.getTenantId(), agentId, publicKey,
                creation.turnstileSecret().strip());
        WebChatSettings settings = new WebChatSettings(config.id(), config.tenantId(), creation.allowedOrigins(),
                creation.turnstileSiteKey(), creation.privacyPolicyUrl(),
                creation.consentMode() == null ? ConsentMode.NOTICE : creation.consentMode());
        ChannelConfig saved = configMapper.toDomain(configRepository.saveAndFlush(configMapper.toEntity(config)));
        settingsRepository.save(toEntity(settings));
        auditRecorder.record(ChannelAuditEvents.channelConfigured(saved, TenantContext.getCurrentTenantId().orElseThrow()));
        return new WebChatChannel(saved, settings);
    }

    /** The web chat {@code channelConfigId}, if it is visible under the current context (RLS). */
    @Transactional(readOnly = true)
    public Optional<WebChatChannel> find(UUID channelConfigId) {
        return configRepository.findById(channelConfigId).map(configMapper::toDomain)
                .filter(ChannelConfig::isPublicKeyRouted)
                .flatMap(config -> settingsRepository.findById(config.id())
                        .map(entity -> new WebChatChannel(config, toDomain(entity))));
    }

    /**
     * The ACTIVE web chat reached by {@code publicKey}, before any tenant context exists: the V59
     * {@code SECURITY DEFINER} function, nothing more. Empty for a malformed, unknown or disabled key,
     * all alike.
     */
    @Transactional(readOnly = true)
    @NoTenantContext
    public Optional<ResolvedWebChat> resolve(String publicKey) {
        if (!PublicKeys.isValidFormat(publicKey)) {
            return Optional.empty();
        }
        return settingsRepository.resolveByPublicKey(publicKey).map(row -> new ResolvedWebChat(
                row.getChannel_config_id(), row.getTenant_id(), row.getAgent_id(), row.getPublic_key(),
                row.getAllowed_origins().isBlank() ? List.of() : Arrays.asList(row.getAllowed_origins().split(" ")),
                row.getTurnstile_site_key(), row.getPrivacy_policy_url(), ConsentMode.valueOf(row.getConsent_mode())));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean requiresSpendLimit(UUID tenantId) {
        return settingsRepository.hasActiveChannel(tenantId);
    }

    private static WebChatSettingsEntity toEntity(WebChatSettings settings) {
        return new WebChatSettingsEntity(settings.channelConfigId(), settings.tenantId(),
                settings.allowedOrigins().toArray(String[]::new), settings.turnstileSiteKey(),
                settings.privacyPolicyUrl(), settings.consentMode());
    }

    private static WebChatSettings toDomain(WebChatSettingsEntity entity) {
        return new WebChatSettings(entity.getChannelConfigId(), entity.getTenantId(),
                List.of(entity.getAllowedOrigins()), entity.getTurnstileSiteKey(), entity.getPrivacyPolicyUrl(),
                entity.getConsentMode());
    }
}
