package dev.cauce.channels;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.config.ChannelConfigNotFoundException;
import dev.cauce.channels.spi.ChannelAdapterRegistry;
import dev.cauce.channels.spi.ChannelDeliveryException;
import dev.cauce.channels.spi.ChannelInboundMessage;
import dev.cauce.channels.spi.ChannelOutboundMessage;
import dev.cauce.channels.spi.InboundChannelAdapter;
import dev.cauce.channels.spi.OutboundChannelAdapter;
import dev.cauce.channels.spi.UnsupportedInboundMessage;
import dev.cauce.channels.spi.UnsupportedInterlocutorException;
import dev.cauce.channels.spi.WebhookRequest;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.InboundMessageService;
import dev.cauce.orchestration.voice.VoiceNoteTranscription;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
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
        IGNORED,
        /**
         * A person's message of a type Cauce does not read yet (a Telegram voice note): not
         * ingested, but audited once and answered with the adapter's fixed text (ADR 0006, W5).
         */
        UNSUPPORTED,
        /**
         * Authentic and well formed, but from an interlocutor no identity kind can name (a
         * Telegram group). Refused loudly — logged at WARN with the reason — and still
         * acknowledged, because the provider would otherwise redeliver it forever.
         */
        REJECTED
    }

    private final ChannelConfigService channelConfigService;
    private final ChannelAdapterRegistry adapterRegistry;
    private final InboundMessageService inboundMessageService;
    private final InboundInterception interception;
    private final UnsupportedMessageRecorder unsupportedRecorder;
    private final TaskExecutor outboundExecutor;
    private final VoiceNoteTranscription voiceNotes;

    public ChannelWebhookService(ChannelConfigService channelConfigService,
                                 ChannelAdapterRegistry adapterRegistry,
                                 InboundMessageService inboundMessageService,
                                 InboundInterception interception,
                                 UnsupportedMessageRecorder unsupportedRecorder,
                                 @Qualifier("channelOutboundExecutor") TaskExecutor outboundExecutor,
                                 VoiceNoteTranscription voiceNotes) {
        this.channelConfigService = channelConfigService;
        this.adapterRegistry = adapterRegistry;
        this.inboundMessageService = inboundMessageService;
        this.interception = interception;
        this.unsupportedRecorder = unsupportedRecorder;
        this.outboundExecutor = outboundExecutor;
        this.voiceNotes = voiceNotes;
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
        Optional<ChannelInboundMessage> message;
        try {
            message = adapter.parse(request.body(), config);
        } catch (UnsupportedInterlocutorException e) {
            log.warn("Rejected {} webhook update for config {}: {}", config.channelType(), configId,
                    e.getMessage());
            return WebhookResult.REJECTED;
        }
        // A voice note is read only by an instance that transcribes (ADR 0012, V3); elsewhere it is
        // a message Cauce does not read, answered as such (ADR 0006, W5).
        if (message.isEmpty() || (message.get().hasAudio() && !voiceNotes.available())) {
            Optional<UnsupportedInboundMessage> unsupported = adapter.unsupported(request.body(), config);
            if (unsupported.isPresent()) {
                answerUnsupported(config, unsupported.get());
                return WebhookResult.UNSUPPORTED;
            }
            log.debug("Ignoring non-ingestable {} webhook payload for config {}",
                    config.channelType(), configId);
            return WebhookResult.IGNORED;
        }
        TenantContext.setCurrentTenantId(config.tenantId());
        try {
            // ADR 0007, B8: a text an interceptor handles (an owner's link code) reaches no agent.
            if (!message.get().hasAudio() && interception.intercepted(config, message.get().identityKind(),
                    message.get().identityValue(), message.get().content())) {
                return WebhookResult.ACCEPTED;
            }
            inboundMessageService.ingestParts(config.agentId(), config.channelType(), config.id(),
                    message.get().identityKind(), message.get().identityValue(),
                    message.get().parts(), message.get().idempotencyKey());
        } finally {
            TenantContext.clear();
        }
        return WebhookResult.ACCEPTED;
    }

    /**
     * W5: never silence. The message is not ingested, so it opens no thread; its receipt makes a
     * redelivery a no-op — one audit record, one reply.
     */
    private void answerUnsupported(ChannelConfig config, UnsupportedInboundMessage unsupported) {
        Optional<String> text;
        TenantContext.setCurrentTenantId(config.tenantId());
        try {
            // The agent's own text (V55), else the adapter's platform default.
            text = unsupportedRecorder.recordUnsupportedMessage(config, unsupported.messageType(),
                    unsupported.providerMessageId(), unsupported.reply());
        } finally {
            TenantContext.clear();
        }
        if (text.isEmpty()) {
            return; // a redelivery: answered the first time
        }
        Optional<OutboundChannelAdapter> outbound = adapterRegistry.outboundAdapter(config.channelType());
        if (outbound.isEmpty()) {
            log.warn("Channel '{}' has no outbound adapter; the {} message through config {} is audited, not answered",
                    config.channelType(), unsupported.messageType(), config.id());
            return;
        }
        // The interlocutor has just written: a channel with a reply window has it open.
        ChannelOutboundMessage reply = new ChannelOutboundMessage(unsupported.identityKind(),
                unsupported.identityValue(), text.get());
        try {
            // Best-effort, off the webhook thread: the provider expects its 2xx quickly.
            outboundExecutor.execute(() -> {
                try {
                    outbound.get().deliver(reply, config);
                } catch (ChannelDeliveryException e) {
                    log.warn("Could not send the text-only reply through config {}: {}", config.id(),
                            e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not queue the text-only reply through config {}: {}", config.id(), e.toString());
        }
    }
}
