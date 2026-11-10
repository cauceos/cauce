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
    private final WebChatProperties properties;

    public WebChatChannelService(ChannelConfigRepository configRepository, ChannelConfigMapper configMapper,
                                 WebChatSettingsRepository settingsRepository, AgentRepository agentRepository,
                                 SpendLimitService spendLimits, AuditEventRecorder auditRecorder,
                                 WebChatProperties properties) {
        this.properties = properties;
        this.configRepository = configRepository;
        this.configMapper = configMapper;
        this.settingsRepository = settingsRepository;
        this.agentRepository = agentRepository;
        this.spendLimits = spendLimits;
        this.auditRecorder = auditRecorder;
    }

    /** What a web chat is created with; the Turnstile secret is sealed at rest, never returned. */
    public record Creation(String turnstileSecret, String turnstileSiteKey, List<String> allowedOrigins,
                           String privacyPolicyUrl, ConsentMode consentMode, WebChatLimits limits,
                           WebChatAppearance appearance) {

        /** A creation that keeps the platform's limits and look. */
        public Creation(String turnstileSecret, String turnstileSiteKey, List<String> allowedOrigins,
                        String privacyPolicyUrl, ConsentMode consentMode) {
            this(turnstileSecret, turnstileSiteKey, allowedOrigins, privacyPolicyUrl, consentMode, null, null);
        }

        /** A creation that keeps the platform's look. */
        public Creation(String turnstileSecret, String turnstileSiteKey, List<String> allowedOrigins,
                        String privacyPolicyUrl, ConsentMode consentMode, WebChatLimits limits) {
            this(turnstileSecret, turnstileSiteKey, allowedOrigins, privacyPolicyUrl, consentMode, limits, null);
        }
    }

    /**
     * Creates a web chat for {@code agentId}, owned by the agent's tenant, with a fresh public key.
     *
     * @throws AgentNotFoundException if the agent is not visible
     * @throws SpendLimitRequiredException if the agent's tenant has no spend limit
     * @throws IllegalArgumentException if a setting is malformed, or a limit higher than the platform's
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
        WebChatLimits limits = creation.limits() == null ? WebChatLimits.NONE
                : creation.limits().validatedAgainst(properties.limits());
        WebChatSettings settings = new WebChatSettings(config.id(), config.tenantId(), creation.allowedOrigins(),
                creation.turnstileSiteKey(), creation.privacyPolicyUrl(),
                creation.consentMode() == null ? ConsentMode.NOTICE : creation.consentMode(), limits);
        ChannelConfig saved = configMapper.toDomain(configRepository.saveAndFlush(configMapper.toEntity(config)));
        WebChatSettingsEntity entity = toEntity(settings);
        entity.setAppearance(write(creation.appearance()));
        settingsRepository.save(entity);
        auditRecorder.record(ChannelAuditEvents.channelConfigured(saved, TenantContext.getCurrentTenantId().orElseThrow()));
        return new WebChatChannel(saved, settings, creation.appearance());
    }

    /**
     * Replaces the look of the web chat {@code channelConfigId} (ADR 0014, W4), at once for every widget
     * that reads its configuration next. Audited as {@code admin.web_chat.appearance_updated}, ids only.
     *
     * @throws dev.cauce.channels.config.ChannelConfigNotFoundException if it is not a visible web chat
     */
    @Transactional
    public WebChatChannel updateAppearance(UUID channelConfigId, WebChatAppearance appearance) {
        WebChatSettingsEntity entity = settingsRepository.findById(channelConfigId).orElseThrow(() ->
                new dev.cauce.channels.config.ChannelConfigNotFoundException("No web chat channel found for id "
                        + channelConfigId));
        entity.setAppearance(write(appearance));
        settingsRepository.save(entity);
        WebChatChannel channel = find(channelConfigId).orElseThrow();
        auditRecorder.record(new dev.cauce.core.audit.AuditEvent(channel.config().tenantId(), APPEARANCE_UPDATED,
                java.util.Map.of("channel_config_id", channelConfigId.toString(),
                        "actor_tenant_id", TenantContext.getCurrentTenantId().orElseThrow().toString())));
        return channel;
    }

    /**
     * Replaces the shop-assistant mode of the web chat {@code channelConfigId} (ADR 0014, WC8), at once for
     * every widget that reads its configuration next; null takes it away. Audited as
     * {@code admin.web_chat.assistant_updated}, ids only.
     *
     * @throws dev.cauce.channels.config.ChannelConfigNotFoundException if it is not a visible web chat
     */
    @Transactional
    public WebChatChannel updateAssistant(UUID channelConfigId, WebChatAssistant assistant) {
        WebChatSettingsEntity entity = settingsRepository.findById(channelConfigId).orElseThrow(() ->
                new dev.cauce.channels.config.ChannelConfigNotFoundException("No web chat channel found for id "
                        + channelConfigId));
        entity.setAssistant(writeJson(assistant));
        settingsRepository.save(entity);
        WebChatChannel channel = find(channelConfigId).orElseThrow();
        auditRecorder.record(new dev.cauce.core.audit.AuditEvent(channel.config().tenantId(), ASSISTANT_UPDATED,
                java.util.Map.of("channel_config_id", channelConfigId.toString(),
                        "actor_tenant_id", TenantContext.getCurrentTenantId().orElseThrow().toString())));
        return channel;
    }

    /** The web chat {@code channelConfigId}, if it is visible under the current context (RLS). */
    @Transactional(readOnly = true)
    public Optional<WebChatChannel> find(UUID channelConfigId) {
        return configRepository.findById(channelConfigId).map(configMapper::toDomain)
                .filter(ChannelConfig::isPublicKeyRouted)
                .flatMap(config -> settingsRepository.findById(config.id())
                        .map(entity -> new WebChatChannel(config, toDomain(entity), read(entity.getAppearance()),
                                readAssistant(entity.getAssistant()))));
    }

    /**
     * The web chat reached by {@code publicKey}, before any tenant context exists: the
     * {@code SECURITY DEFINER} function (V61), nothing more. Empty for a malformed or unknown key, alike;
     * a disabled one resolves with {@code active} false, so it can be refused clearly.
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
                row.getTurnstile_site_key(), row.getPrivacy_policy_url(), ConsentMode.valueOf(row.getConsent_mode()),
                "ACTIVE".equals(row.getStatus()), new WebChatLimits(row.getSessions_per_ip_hour(),
                        row.getMessages_per_session_minute(), row.getMessages_per_session_day(),
                        row.getMessages_per_ip_minute(), row.getReads_per_session_minute()),
                read(row.getAppearance()), readAssistant(row.getAssistant())));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean requiresSpendLimit(UUID tenantId) {
        return settingsRepository.hasActiveChannel(tenantId);
    }

    static final String APPEARANCE_UPDATED = "admin.web_chat.appearance_updated";
    static final String ASSISTANT_UPDATED = "admin.web_chat.assistant_updated";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String write(WebChatAppearance appearance) {
        return writeJson(appearance);
    }

    private static String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The stored look; the neutral one when none is stored. A stored one was validated when written. */
    private static WebChatAppearance read(String json) {
        if (json == null || json.isBlank()) {
            return WebChatAppearance.NEUTRAL;
        }
        try {
            return JSON.readValue(json, WebChatAppearance.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("A stored web-chat appearance cannot be read", e);
        }
    }

    /** The stored shop-assistant mode; null when none is stored. A stored one was validated when written. */
    private static WebChatAssistant readAssistant(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, WebChatAssistant.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("A stored web-chat assistant mode cannot be read", e);
        }
    }

    private static WebChatSettingsEntity toEntity(WebChatSettings settings) {
        return new WebChatSettingsEntity(settings.channelConfigId(), settings.tenantId(),
                settings.allowedOrigins().toArray(String[]::new), settings.turnstileSiteKey(),
                settings.privacyPolicyUrl(), settings.consentMode(), settings.limits());
    }

    private static WebChatSettings toDomain(WebChatSettingsEntity entity) {
        return new WebChatSettings(entity.getChannelConfigId(), entity.getTenantId(),
                List.of(entity.getAllowedOrigins()), entity.getTurnstileSiteKey(), entity.getPrivacyPolicyUrl(),
                entity.getConsentMode(), entity.getLimits());
    }
}
