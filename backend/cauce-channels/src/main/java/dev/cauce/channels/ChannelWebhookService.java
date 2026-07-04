package dev.cauce.channels;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.config.ChannelConfigNotFoundException;
import dev.cauce.channels.spi.ChannelAdapterRegistry;
import dev.cauce.channels.spi.ChannelInboundMessage;
import dev.cauce.channels.spi.InboundChannelAdapter;
import dev.cauce.channels.spi.WebhookRequest;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.InboundMessageService;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The channel-agnostic inbound webhook flow: resolve the config (and thereby the tenant),
 * authenticate the request, normalize the payload, and ingest through the existing
 * boundary. The order is a security invariant: <strong>nothing is processed before
 * {@link InboundChannelAdapter#verify} passes</strong>.
 *
 * <p>Deliberately not {@code @Transactional}: resolution and ingest are each their own
 * transaction ({@code resolveActiveChannelConfig} is the cross-tenant escape hatch;
 * {@code ingest} runs under RLS in the config's tenant context, which this service
 * establishes around the call — the webhook thread carries no prior context).
 *
 * <p>Provider redelivery is deduplicated for free: the adapter derives the idempotency
 * key from the provider's stable delivery id, and the ingest replay path returns the
 * original result with no second message, invocation, or event.
 */
@Service
public class ChannelWebhookService {

    private static final Logger log = LoggerFactory.getLogger(ChannelWebhookService.class);

    /** Outcome of handling an authentic webhook request. */
    public enum WebhookResult {
        /** The payload was ingested (or replayed onto an earlier ingest). */
        ACCEPTED,
        /** Authentic but not an ingestable user message; acknowledged without effects. */
        IGNORED
    }

    private final ChannelConfigService channelConfigService;
    private final ChannelAdapterRegistry adapterRegistry;
    private final InboundMessageService inboundMessageService;

    public ChannelWebhookService(ChannelConfigService channelConfigService,
                                 ChannelAdapterRegistry adapterRegistry,
                                 InboundMessageService inboundMessageService) {
        this.channelConfigService = channelConfigService;
        this.adapterRegistry = adapterRegistry;
        this.inboundMessageService = inboundMessageService;
    }

    /**
     * Handles one provider webhook request addressed to {@code configId}.
     *
     * @throws ChannelConfigNotFoundException if the config does not exist or is not ACTIVE
     * @throws WebhookAuthenticationException if the request fails the adapter's check
     * @throws dev.cauce.channels.spi.ChannelPayloadException if the payload is malformed
     */
    public WebhookResult handle(UUID configId, WebhookRequest request) {
        ChannelConfig config = channelConfigService.resolveActiveChannelConfig(configId)
                .orElseThrow(() -> new ChannelConfigNotFoundException(
                        "No active channel config found for id " + configId));
        InboundChannelAdapter adapter = adapterRegistry.requireInboundAdapter(config.channelType());
        if (!adapter.verify(request, config)) {
            throw new WebhookAuthenticationException(
                    "Webhook authentication failed for channel config " + configId);
        }
        Optional<ChannelInboundMessage> message = adapter.parse(request.body(), config);
        if (message.isEmpty()) {
            log.debug("Ignoring non-ingestable {} webhook payload for config {}",
                    config.channelType(), configId);
            return WebhookResult.IGNORED;
        }
        TenantContext.setCurrentTenantId(config.tenantId());
        try {
            inboundMessageService.ingest(config.agentId(), config.channelType(),
                    message.get().externalIdentityRef(), message.get().content(),
                    message.get().idempotencyKey());
        } finally {
            TenantContext.clear();
        }
        return WebhookResult.ACCEPTED;
    }
}
