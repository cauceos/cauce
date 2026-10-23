package dev.cauce.channels;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelAdapterRegistry;
import dev.cauce.channels.spi.ChannelOutboundMessage;
import dev.cauce.channels.spi.OutboundChannelAdapter;
import dev.cauce.core.LogKeys;
import dev.cauce.core.conversation.AgentReplyDispatcher;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.identity.Identity;
import dev.cauce.core.message.Message;
import dev.cauce.core.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Channel-layer implementation of the {@link AgentReplyDispatcher} port: resolves the
 * channel instance the conversation came in through and delivers the committed agent reply
 * through the outbound SPI. Best-effort by contract — never throws into the orchestrator,
 * never blocks it on network I/O: the delivery runs on the dedicated
 * {@code channelOutboundExecutor}, and every failure is logged and swallowed (the reply is
 * already persisted and stays readable by polling the messages API).
 *
 * <p>The channel instance is the conversation's {@code channel_config_id} (ADR 0004, PC-1),
 * and the channel is that config's: the conversation does not carry a channel type of its own
 * (PC-2). A conversation without a config — the built-in {@code "api"} channel, or a legacy
 * thread whose origin the V26 backfill could not tell — is a clean no-op decided
 * synchronously, before anything is queued. A config that is no longer ACTIVE, or whose
 * channel has no outbound adapter (inbound-only), is skipped on the executor.
 *
 * <p>The tenant context is captured on the dispatching thread (the invocation worker holds
 * the row's tenant) and re-established inside the async task — {@code TenantContext} is a
 * ThreadLocal and does not propagate — so the config and identity reads run under RLS.
 *
 * <p>TODO(delivery guarantee): fire-and-forget by design for now. When traffic justifies
 * guaranteed delivery, replace the executor hand-off with an outbox write (persist the
 * delivery intent here; a drainer delivers and retries) — additive behind the same port.
 */
@Service
public class OutboundDeliveryService implements AgentReplyDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboundDeliveryService.class);

    private final ChannelAdapterRegistry adapterRegistry;
    private final ChannelConfigService channelConfigService;
    private final InterlocutorLookup interlocutorLookup;
    private final TaskExecutor outboundExecutor;

    public OutboundDeliveryService(ChannelAdapterRegistry adapterRegistry,
                                   ChannelConfigService channelConfigService,
                                   InterlocutorLookup interlocutorLookup,
                                   @Qualifier("channelOutboundExecutor") TaskExecutor outboundExecutor) {
        this.adapterRegistry = adapterRegistry;
        this.channelConfigService = channelConfigService;
        this.interlocutorLookup = interlocutorLookup;
        this.outboundExecutor = outboundExecutor;
    }

    @Override
    public void dispatchAgentReply(Conversation conversation, Message agentReply) {
        Optional<UUID> configId = conversation.channelConfigId();
        if (configId.isEmpty()) {
            log.debug("Conversation {} has no channel config; reply {} is poll-only",
                    conversation.id(), agentReply.id());
            return;
        }
        Optional<UUID> tenantId = TenantContext.getCurrentTenantId();
        if (tenantId.isEmpty()) {
            log.warn("No tenant context on the dispatching thread; skipping outbound delivery "
                    + "for conversation {}", conversation.id());
            return;
        }
        try {
            outboundExecutor.execute(() ->
                    deliverUnderTenant(tenantId.get(), configId.get(), conversation, agentReply));
        } catch (RuntimeException e) {
            log.warn("Could not queue outbound delivery for conversation {}: {}",
                    conversation.id(), e.toString(), e);
        }
    }

    /** Runs on the outbound executor: everything is best-effort and fully caught. */
    private void deliverUnderTenant(UUID tenantId, UUID configId, Conversation conversation,
                                    Message agentReply) {
        TenantContext.setCurrentTenantId(tenantId);
        MDC.put(LogKeys.TENANT_ID, tenantId.toString()); // ADR 0011, L1
        MDC.put(LogKeys.CONVERSATION_ID, conversation.id().toString());
        try {
            Optional<ChannelConfig> config = channelConfigService.findActiveConfig(configId);
            if (config.isEmpty()) {
                // Disabled (or removed) after the conversation started: delivering through any
                // other instance would be worse than the poll fallback.
                log.warn("Skipping outbound delivery for conversation {}: channel config {} is not ACTIVE",
                        conversation.id(), configId);
                return;
            }
            String channelType = config.get().channelType();
            Optional<OutboundChannelAdapter> adapter = adapterRegistry.outboundAdapter(channelType);
            if (adapter.isEmpty()) {
                log.debug("Channel '{}' has no outbound adapter; reply {} is poll-only",
                        channelType, agentReply.id());
                return;
            }
            Optional<Identity> interlocutor = interlocutorLookup.find(conversation.identityId());
            if (interlocutor.isEmpty()) {
                log.warn("Skipping outbound delivery for conversation {}: its identity {} is not visible",
                        conversation.id(), conversation.identityId());
                return;
            }
            // The destination is the typed pair the inbound adapter produced (ADR 0004).
            adapter.get().deliver(new ChannelOutboundMessage(interlocutor.get().kind(),
                    interlocutor.get().value(), agentReply.content(),
                    interlocutorLookup.lastMessageAt(conversation.id()).orElse(null)), config.get());
            log.debug("Delivered reply {} through channel '{}' for conversation {}",
                    agentReply.id(), channelType, conversation.id());
        } catch (RuntimeException e) {
            log.warn("Outbound delivery failed for conversation {} through config {} (best-effort; "
                            + "the reply stays readable via the messages API): {}",
                    conversation.id(), configId, e.toString(), e);
        } finally {
            TenantContext.clear();
            MDC.remove(LogKeys.TENANT_ID);
            MDC.remove(LogKeys.CONVERSATION_ID);
        }
    }
}
