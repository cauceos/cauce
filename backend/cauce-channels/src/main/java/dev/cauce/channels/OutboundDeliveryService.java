package dev.cauce.channels;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelAdapterRegistry;
import dev.cauce.channels.spi.ChannelOutboundMessage;
import dev.cauce.channels.spi.OutboundChannelAdapter;
import dev.cauce.core.conversation.AgentReplyDispatcher;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.message.Message;
import dev.cauce.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Channel-layer implementation of the {@link AgentReplyDispatcher} port: resolves the
 * conversation's channel instance and delivers the committed agent reply through the
 * outbound SPI. Best-effort by contract — never throws into the orchestrator, never blocks
 * it on network I/O: the delivery runs on the dedicated {@code channelOutboundExecutor},
 * and every failure is logged and swallowed (the reply is already persisted and stays
 * readable by polling the messages API).
 *
 * <p>A channel without an outbound adapter (the built-in {@code "api"} channel, or an
 * inbound-only channel) is a clean no-op decided synchronously, before anything is queued.
 *
 * <p>The tenant context is captured on the dispatching thread (the invocation worker holds
 * the row's tenant) and re-established inside the async task — {@code TenantContext} is a
 * ThreadLocal and does not propagate — so the config resolution runs under RLS.
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
    private final TaskExecutor outboundExecutor;

    public OutboundDeliveryService(ChannelAdapterRegistry adapterRegistry,
                                   ChannelConfigService channelConfigService,
                                   @Qualifier("channelOutboundExecutor") TaskExecutor outboundExecutor) {
        this.adapterRegistry = adapterRegistry;
        this.channelConfigService = channelConfigService;
        this.outboundExecutor = outboundExecutor;
    }

    @Override
    public void dispatchAgentReply(Conversation conversation, Message agentReply) {
        Optional<OutboundChannelAdapter> adapter =
                adapterRegistry.outboundAdapter(conversation.channelType());
        if (adapter.isEmpty()) {
            log.debug("Channel '{}' has no outbound adapter; reply {} is poll-only",
                    conversation.channelType(), agentReply.id());
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
                    deliverUnderTenant(tenantId.get(), adapter.get(), conversation, agentReply));
        } catch (RuntimeException e) {
            log.warn("Could not queue outbound delivery for conversation {}: {}",
                    conversation.id(), e.toString(), e);
        }
    }

    /** Runs on the outbound executor: everything is best-effort and fully caught. */
    private void deliverUnderTenant(UUID tenantId, OutboundChannelAdapter adapter,
                                    Conversation conversation, Message agentReply) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            List<ChannelConfig> configs = channelConfigService.findActiveConfigs(
                    conversation.agentId(), conversation.channelType());
            if (configs.size() != 1) {
                // 0: the binding was disabled/removed after the conversation started. >1: the
                // origin is ambiguous — conversations do not record their originating config
                // (TODO: stamp channel_config_id on conversations when the delivery-guarantee
                // module lands) and delivering through the wrong instance is worse than the
                // poll fallback.
                log.warn("Skipping outbound delivery for conversation {}: {} ACTIVE '{}' config(s) "
                                + "for agent {}", conversation.id(), configs.size(),
                        conversation.channelType(), conversation.agentId());
                return;
            }
            adapter.deliver(new ChannelOutboundMessage(
                    conversation.externalIdentityRef(), agentReply.content()), configs.get(0));
            log.debug("Delivered reply {} through channel '{}' for conversation {}",
                    agentReply.id(), conversation.channelType(), conversation.id());
        } catch (RuntimeException e) {
            log.warn("Outbound delivery failed for conversation {} on channel '{}' (best-effort; "
                            + "the reply stays readable via the messages API): {}",
                    conversation.id(), conversation.channelType(), e.toString(), e);
        } finally {
            TenantContext.clear();
        }
    }
}
