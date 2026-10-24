package dev.cauce.channels.whatsapp;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.channels.UnsupportedMessageRecorder;
import dev.cauce.channels.WebhookAuthenticationException;
import dev.cauce.channels.InboundInterception;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelDeliveryException;
import dev.cauce.channels.spi.ChannelOutboundMessage;
import dev.cauce.channels.spi.ChannelPayloadException;
import dev.cauce.channels.spi.WebhookRequest;
import dev.cauce.channels.whatsapp.app.WhatsAppApp;
import dev.cauce.channels.whatsapp.app.WhatsAppAppNotFoundException;
import dev.cauce.channels.whatsapp.app.WhatsAppAppService;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.message.MessagePart;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.InboundMessageService;
import dev.cauce.orchestration.voice.VoiceNoteTranscription;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * The inbound half of the WhatsApp Cloud API channel (ADR 0006). App-routed, so it is not an
 * {@code InboundChannelAdapter} (one URL, one config, one message): one Meta app posts the events
 * of every number it serves to {@code /webhooks/whatsapp/{appId}}, in batches.
 *
 * <p>The order is a security invariant (W2): resolve the app from the URL, verify
 * {@code X-Hub-Signature-256} over the raw body with the app's secret, and only then read the body
 * and route each change by {@code metadata.phone_number_id} to its number, within that app.
 *
 * <p>Per message:
 * <ul>
 *   <li><b>text</b> — ingested through the channel-agnostic ingest under the number's tenant,
 *       from the BSUID ({@code from_user_id}) as {@code PROVIDER_USER_ID} (W1), deduplicated by
 *       the provider's message id;</li>
 *   <li><b>system</b> (a changed number or BSUID) — a WARN: the new BSUID will be a new identity
 *       and a new thread (W1's conscious limitation);</li>
 *   <li><b>a group message</b> — refused with a WARN, as for Telegram groups (ADR 0004);</li>
 *   <li><b>any other type</b> — audited as {@code conduct.message.unsupported} and answered with
 *       the fixed text-only reply (W5): never silence. Exactly once: a receipt keyed by the
 *       provider message id (V32) turns a redelivery into a no-op.</li>
 * </ul>
 * Status updates (sent, delivered, read, failed) are acknowledged and ignored. After a valid
 * signature everything is acknowledged with a 2xx except a malformed body, so Meta never
 * redelivers what can never be accepted.
 */
@Service
public class WhatsAppWebhookService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookService.class);

    private final WhatsAppAppService appService;
    private final WhatsAppNumberService numberService;
    private final InboundMessageService inboundMessageService;
    private final WhatsAppChannelAdapter outbound;
    private final UnsupportedMessageRecorder eventRecorder;
    private final WhatsAppProperties properties;
    private final ApiKeyHasher hasher;
    private final TaskExecutor outboundExecutor;
    private final InboundInterception interception;
    private final Clock clock;
    private final VoiceNoteTranscription voiceNotes;
    // Own mapper: Meta's wire format must not inherit the application's Jackson settings.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WhatsAppWebhookService(WhatsAppAppService appService, WhatsAppNumberService numberService,
                                  InboundMessageService inboundMessageService, WhatsAppChannelAdapter outbound,
                                  UnsupportedMessageRecorder eventRecorder, WhatsAppProperties properties,
                                  ApiKeyHasher hasher,
                                  @Qualifier("channelOutboundExecutor") TaskExecutor outboundExecutor,
                                  InboundInterception interception,
                                  Clock clock,
                                  VoiceNoteTranscription voiceNotes) {
        this.appService = appService;
        this.numberService = numberService;
        this.inboundMessageService = inboundMessageService;
        this.outbound = outbound;
        this.eventRecorder = eventRecorder;
        this.properties = properties;
        this.hasher = hasher;
        this.outboundExecutor = outboundExecutor;
        this.interception = interception;
        this.clock = clock;
        this.voiceNotes = voiceNotes;
    }

    /**
     * Answers Meta's subscription check: {@code hub.mode=subscribe} and the app's verify token
     * return {@code hub.challenge}, verbatim.
     *
     * @throws WhatsAppAppNotFoundException if no ACTIVE app has this id
     * @throws WebhookAuthenticationException if the mode or the token does not match
     */
    public String verifySubscription(UUID appId, String mode, String verifyToken, String challenge) {
        WhatsAppApp app = appService.resolveActiveApp(appId).orElseThrow(() ->
                new WhatsAppAppNotFoundException("No active WhatsApp app found for id " + appId));
        if (!"subscribe".equals(mode) || verifyToken == null || challenge == null
                || !hasher.matches(verifyToken, app.verifyTokenHash())) {
            throw new WebhookAuthenticationException("WhatsApp subscription check failed for app " + appId);
        }
        return challenge;
    }

    /**
     * Handles one signed event POST for {@code appId}.
     *
     * @throws WhatsAppAppNotFoundException if no ACTIVE app has this id
     * @throws WebhookAuthenticationException if the signature does not verify
     * @throws ChannelPayloadException if the (authentic) body is not a WhatsApp webhook
     */
    public void handle(UUID appId, WebhookRequest request) {
        WhatsAppApp app = appService.resolveActiveApp(appId).orElseThrow(() ->
                new WhatsAppAppNotFoundException("No active WhatsApp app found for id " + appId));
        // Nothing in the body is read before this line (W2).
        if (!WhatsAppSignature.matches(request.header(WhatsAppSignature.HEADER).orElse(null),
                request.rawBody(), app.appSecret())) {
            throw new WebhookAuthenticationException("WhatsApp signature check failed for app " + appId);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(request.rawBody());
        } catch (JacksonException e) {
            throw new ChannelPayloadException("Malformed WhatsApp webhook payload", e);
        } catch (java.io.IOException e) {
            throw new ChannelPayloadException("Unreadable WhatsApp webhook payload", e);
        }
        if (!root.path("entry").isArray()) {
            throw new ChannelPayloadException("WhatsApp webhook payload has no entry array");
        }
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                if ("messages".equals(change.path("field").asText())) {
                    handleChange(appId, change.path("value"));
                }
            }
        }
    }

    private void handleChange(UUID appId, JsonNode value) {
        String phoneNumberId = value.path("metadata").path("phone_number_id").asText("");
        JsonNode messages = value.path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            return; // statuses and other notifications: acknowledged, not acted on
        }
        Optional<ChannelConfig> number = phoneNumberId.isBlank()
                ? Optional.empty()
                : numberService.resolveNumber(appId, phoneNumberId);
        if (number.isEmpty()) {
            log.warn("WhatsApp app {} received messages for a number it does not serve (or not ACTIVE); ignored",
                    appId);
            return;
        }
        for (JsonNode message : messages) {
            handleMessage(number.get(), message, value.path("contacts"));
        }
    }

    private void handleMessage(ChannelConfig number, JsonNode message, JsonNode contacts) {
        String type = message.path("type").asText("unknown");
        String providerMessageId = message.path("id").asText("");
        if ("system".equals(type)) {
            // W1: a changed number or BSUID; the new BSUID is a new identity, history is not joined.
            log.warn("WhatsApp system message for config {} ({}): the interlocutor's next message opens a new "
                    + "identity and thread", number.id(), message.path("system").path("type").asText("?"));
            return;
        }
        if (message.has("group_id")) {
            log.warn("Rejected a WhatsApp group message for config {}: a group is not a person", number.id());
            return;
        }
        String bsuid = bsuidOf(message, contacts);
        if (bsuid.isBlank()) {
            log.warn("WhatsApp message {} for config {} carries no BSUID (from_user_id); not ingested",
                    providerMessageId, number.id());
            return;
        }
        TenantContext.setCurrentTenantId(number.tenantId());
        try {
            if ("text".equals(type) && !message.path("text").path("body").asText("").isBlank()) {
                // ADR 0007, B8: a text an interceptor handles (an owner's link code) reaches no agent.
                if (interception.intercepted(number, IdentityKind.PROVIDER_USER_ID, bsuid,
                        message.path("text").path("body").asText())) {
                    return;
                }
                inboundMessageService.ingest(number.agentId(), number.channelType(), number.id(),
                        IdentityKind.PROVIDER_USER_ID, bsuid, message.path("text").path("body").asText(),
                        number.id() + ":" + providerMessageId);
                return;
            }
            // ADR 0012: a voice note is read when the instance transcribes; the worker fetches it
            // by its media id. WhatsApp announces neither its size nor its length.
            if ("audio".equals(type) && voiceNotes.available()
                    && !message.path("audio").path("id").asText("").isBlank()) {
                JsonNode audio = message.path("audio");
                inboundMessageService.ingestParts(number.agentId(), number.channelType(), number.id(),
                        IdentityKind.PROVIDER_USER_ID, bsuid,
                        List.of(MessagePart.Audio.received(audio.path("mime_type").asText("audio/ogg"),
                                audio.path("id").asText(), null, null)),
                        number.id() + ":" + providerMessageId);
                return;
            }
            // W5: never silence. The message is not ingested, so it opens no thread. Its receipt
            // makes a redelivery a no-op: one audit record, one reply.
            if (providerMessageId.isBlank()) {
                log.warn("WhatsApp {} message for config {} carries no id; it cannot be deduplicated, so it is "
                        + "neither recorded nor answered", type, number.id());
                return;
            }
            // The agent's own text (V55), else the platform's.
            eventRecorder.recordUnsupportedMessage(number, type, providerMessageId,
                    properties.getUnsupportedMessageReply()).ifPresent(text -> sendTextOnlyReply(number, bsuid, text));
        } finally {
            TenantContext.clear();
        }
    }

    /** Best-effort, off the webhook thread: Meta expects the 2xx quickly. */
    private void sendTextOnlyReply(ChannelConfig number, String bsuid, String text) {
        // The interlocutor has just written, so the 24-hour window is open.
        ChannelOutboundMessage reply = new ChannelOutboundMessage(IdentityKind.PROVIDER_USER_ID, bsuid,
                text, clock.instant());
        try {
            outboundExecutor.execute(() -> {
                try {
                    outbound.deliver(reply, number);
                } catch (ChannelDeliveryException e) {
                    log.warn("Could not send the text-only reply through config {}: {}", number.id(),
                            e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not queue the text-only reply through config {}: {}", number.id(), e.toString());
        }
    }

    /** The sender's BSUID: {@code from_user_id}, else the matching contact's {@code user_id}. */
    private static String bsuidOf(JsonNode message, JsonNode contacts) {
        String fromUserId = message.path("from_user_id").asText("");
        if (!fromUserId.isBlank()) {
            return fromUserId;
        }
        if (contacts.isArray() && contacts.size() == 1) {
            return contacts.get(0).path("user_id").asText("");
        }
        return "";
    }
}
