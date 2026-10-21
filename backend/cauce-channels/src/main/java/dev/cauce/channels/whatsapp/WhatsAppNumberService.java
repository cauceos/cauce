package dev.cauce.channels.whatsapp;

import dev.cauce.channels.ChannelConfigCreationResult;
import dev.cauce.channels.audit.ChannelAuditEvents;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.persistence.ChannelConfigMapper;
import dev.cauce.channels.persistence.ChannelConfigRepository;
import dev.cauce.channels.whatsapp.app.WhatsAppApp;
import dev.cauce.channels.whatsapp.app.WhatsAppAppMapper;
import dev.cauce.channels.whatsapp.app.WhatsAppAppNotFoundException;
import dev.cauce.channels.whatsapp.app.WhatsAppAppRepository;
import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.tenant.NoTenantContext;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentRepository;
import dev.cauce.memory.tenant.TenantEntity;
import dev.cauce.memory.tenant.TenantRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Binds WhatsApp numbers to agents and routes webhook events to them (ADR 0006, W2). A number is
 * a {@code whatsapp} channel config carrying the app that receives its webhooks and Meta's
 * {@code phone_number_id}; its access token is the config's credential, sealed at rest (W3).
 */
@Service
public class WhatsAppNumberService {

    /** The tenant hierarchy has three fixed levels, so no chain is longer than this. */
    private static final int MAX_HIERARCHY_DEPTH = 3;

    private final ChannelConfigRepository channelConfigRepository;
    private final ChannelConfigMapper channelConfigMapper;
    private final AgentRepository agentRepository;
    private final WhatsAppAppRepository appRepository;
    private final WhatsAppAppMapper appMapper;
    private final TenantRepository tenantRepository;
    private final AuditEventRecorder auditRecorder;

    public WhatsAppNumberService(ChannelConfigRepository channelConfigRepository,
                                 ChannelConfigMapper channelConfigMapper,
                                 AgentRepository agentRepository,
                                 WhatsAppAppRepository appRepository,
                                 WhatsAppAppMapper appMapper,
                                 TenantRepository tenantRepository,
                                 AuditEventRecorder auditRecorder) {
        this.channelConfigRepository = channelConfigRepository;
        this.channelConfigMapper = channelConfigMapper;
        this.agentRepository = agentRepository;
        this.appRepository = appRepository;
        this.appMapper = appMapper;
        this.tenantRepository = tenantRepository;
        this.auditRecorder = auditRecorder;
    }

    /**
     * Binds the number {@code phoneNumberId}, served by the app {@code appId}, to {@code agentId}.
     * Agent and app must be visible under the current context (RLS); the app must belong to the
     * agent's tenant or an ancestor of it. Audited as {@code admin.channel.configured}.
     *
     * @throws AgentNotFoundException if the agent is not visible
     * @throws WhatsAppAppNotFoundException if the app is not visible
     * @throws AppOutsideHierarchyException if the app's tenant is not the agent's or above it
     * @throws PhoneNumberAlreadyBoundException if the number is bound on this instance already
     */
    @Transactional
    public ChannelConfigCreationResult bindNumber(UUID agentId, UUID appId, String phoneNumberId,
                                                  String accessToken) {
        AgentEntity agent = agentRepository.findById(agentId).orElseThrow(() ->
                new AgentNotFoundException("No agent found for id " + agentId));
        WhatsAppApp app = appRepository.findById(appId).map(appMapper::toDomain).orElseThrow(() ->
                new WhatsAppAppNotFoundException("No WhatsApp app found for id " + appId));
        if (!isSelfOrAncestor(app.tenantId(), agent.getTenantId())) {
            throw new AppOutsideHierarchyException("WhatsApp app " + appId
                    + " does not belong to the agent's tenant or one above it");
        }
        ChannelConfig config = ChannelConfig.createWhatsAppNumber(agent.getTenantId(), agentId, appId,
                phoneNumberId, accessToken);
        ChannelConfig saved;
        try {
            saved = channelConfigMapper.toDomain(
                    channelConfigRepository.saveAndFlush(channelConfigMapper.toEntity(config)));
        } catch (DataIntegrityViolationException e) {
            throw new PhoneNumberAlreadyBoundException("WhatsApp number " + config.phoneNumberId()
                    + " is already bound on this instance");
        }
        auditRecorder.record(ChannelAuditEvents.channelConfigured(
                saved, TenantContext.getCurrentTenantId().orElseThrow()));
        return new ChannelConfigCreationResult(saved, null);
    }

    /**
     * Routes a verified WhatsApp event: the ACTIVE number {@code phoneNumberId} of exactly the app
     * {@code appId}. {@link NoTenantContext}: the webhook carries none; resolved through the V31
     * {@code SECURITY DEFINER} function (ADR 0001).
     */
    @Transactional(readOnly = true)
    @NoTenantContext
    public Optional<ChannelConfig> resolveNumber(UUID appId, String phoneNumberId) {
        return channelConfigRepository.resolveWhatsAppNumber(appId, phoneNumberId)
                .map(channelConfigMapper::toDomain);
    }

    /**
     * Whether {@code candidate} is {@code tenantId} or above it. Walks up from the agent's tenant;
     * every row on the way is visible whenever the app is, since an actor that sees the app is at
     * or above the app's tenant.
     */
    private boolean isSelfOrAncestor(UUID candidate, UUID tenantId) {
        UUID current = tenantId;
        for (int depth = 0; depth < MAX_HIERARCHY_DEPTH && current != null; depth++) {
            if (current.equals(candidate)) {
                return true;
            }
            current = tenantRepository.findById(current).map(TenantEntity::getParentTenantId).orElse(null);
        }
        return false;
    }
}
