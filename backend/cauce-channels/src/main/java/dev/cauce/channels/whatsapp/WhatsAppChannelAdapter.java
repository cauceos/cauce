package dev.cauce.channels.whatsapp;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelDeliveryException;
import dev.cauce.channels.spi.ChannelMediaException;
import dev.cauce.channels.spi.MediaChannelAdapter;
import dev.cauce.channels.spi.OutsideReplyWindowException;
import dev.cauce.channels.spi.ReplyMarkdown;
import dev.cauce.channels.spi.ChannelOutboundMessage;
import dev.cauce.channels.spi.OutboundChannelAdapter;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.orchestration.exception.VoiceNoteRejectedException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Outbound half of the WhatsApp Cloud API channel (ADR 0006): sends a text reply with
 * {@code POST {base-url}/{version}/{phone_number_id}/messages}, authenticated with the number's
 * access token, addressed to the interlocutor's BSUID ({@code recipient}, never {@code to} — W1:
 * no phone number is stored).
 *
 * <p>Loud failures, never degradation (ADR 0004, decision 3; W4):
 * <ul>
 *   <li>a message outside the 24-hour customer service window — measured from the interlocutor's
 *       last message, which the caller supplies — is not sent: templates are a later unit;</li>
 *   <li>a text longer than {@value #MAX_TEXT_LENGTH} characters is not split;</li>
 *   <li>a destination other than a BSUID ({@code PROVIDER_USER_ID}) is refused.</li>
 * </ul>
 * Every refusal and every provider error is a {@link ChannelDeliveryException}, which the
 * best-effort delivery logs with its reason. The access token never appears in a message.
 *
 * <p>The typing indicator ({@link #showTypingFor}) is tied to the message it answers, not to the
 * interlocutor: it marks that message read and shows "escribiendo…" for up to 25 seconds or until
 * the reply.
 */
public class WhatsAppChannelAdapter implements OutboundChannelAdapter, MediaChannelAdapter {

    static final String CHANNEL_TYPE = "whatsapp";
    static final int MAX_TEXT_LENGTH = 4096;
    static final Duration SERVICE_WINDOW = Duration.ofHours(24);
    /** Meta's error for a free-form message outside the customer service window. */
    static final int REENGAGEMENT_ERROR = 131047;

    // Own mapper: the Graph API wire format must not inherit the application's Jackson settings.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final WhatsAppProperties properties;
    private final Clock clock;

    public WhatsAppChannelAdapter(HttpClient httpClient, WhatsAppProperties properties, Clock clock) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String channelType() {
        return CHANNEL_TYPE;
    }

    @Override
    public void deliver(ChannelOutboundMessage message, ChannelConfig config) {
        if (message.identityKind() != IdentityKind.PROVIDER_USER_ID) {
            throw new ChannelDeliveryException("WhatsApp delivers to a BSUID (PROVIDER_USER_ID), not "
                    + message.identityKind());
        }
        if (!config.isAppRouted()) {
            throw new ChannelDeliveryException("Channel config " + config.id() + " is not a WhatsApp number");
        }
        Instant lastInbound = message.lastMessageAt().orElseThrow(() ->
                new OutsideReplyWindowException("The 24-hour window cannot be checked: no message from the interlocutor"));
        if (Duration.between(lastInbound, clock.instant()).compareTo(SERVICE_WINDOW) > 0) {
            throw new OutsideReplyWindowException("Outside the WhatsApp 24-hour customer service window "
                    + "(last interlocutor message at " + lastInbound + "); templates are not supported yet");
        }
        // WhatsApp has its own markers: the agent's Markdown is rendered into them, never sent verbatim.
        String body = ReplyMarkdown.toWhatsApp(message.content());
        if (body.length() > MAX_TEXT_LENGTH) {
            throw new ChannelDeliveryException("Reply of " + body.length()
                    + " characters exceeds the WhatsApp text limit of " + MAX_TEXT_LENGTH + "; it is not split");
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messaging_product", "whatsapp");
        payload.put("recipient_type", "individual");
        payload.put("recipient", message.identityValue());
        payload.put("type", "text");
        ObjectNode text = payload.putObject("text");
        text.put("body", body);
        text.put("preview_url", false);

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getBaseUrl() + "/"
                        + properties.getApiVersion() + "/" + config.phoneNumberId() + "/messages"))
                .timeout(properties.getTimeout())
                .header("Authorization", "Bearer " + config.credential())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ChannelDeliveryException("HTTP call to the WhatsApp Cloud API failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelDeliveryException("Interrupted during WhatsApp delivery", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String described = describeError(response.body());
            String failure = "WhatsApp send returned HTTP " + response.statusCode() + ": " + described;
            // Meta's own re-engagement refusal is the window, seen from its side.
            throw described.startsWith("error " + REENGAGEMENT_ERROR + " ")
                    ? new OutsideReplyWindowException(failure)
                    : new ChannelDeliveryException(failure);
        }
    }

    /**
     * Shows the interlocutor of inbound message {@code providerMessageId} (its {@code wamid}) the
     * typing indicator, which also marks that message read. Sent when a voice note arrives, before
     * it is heard: the indicator lasts while it is transcribed.
     *
     * @throws ChannelDeliveryException if Meta refuses it; the caller only logs it
     */
    public void showTypingFor(String providerMessageId, ChannelConfig config) {
        if (!config.isAppRouted()) {
            throw new ChannelDeliveryException("Channel config " + config.id() + " is not a WhatsApp number");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messaging_product", "whatsapp");
        payload.put("status", "read");
        payload.put("message_id", providerMessageId);
        payload.putObject("typing_indicator").put("type", "text");
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getBaseUrl() + "/"
                        + properties.getApiVersion() + "/" + config.phoneNumberId() + "/messages"))
                .timeout(properties.getTimeout())
                .header("Authorization", "Bearer " + config.credential())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ChannelDeliveryException("HTTP call to the WhatsApp Cloud API failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelDeliveryException("Interrupted while showing the WhatsApp typing indicator", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ChannelDeliveryException("WhatsApp typing indicator returned HTTP " + response.statusCode()
                    + ": " + describeError(response.body()));
        }
    }

    /**
     * The bytes of a voice note (ADR 0012, V1): the Graph API names the media id's short-lived URL and
     * its size, then the file is read from that URL, both with the number's access token. Never more
     * than {@code maxBytes}.
     */
    @Override
    public byte[] download(String providerReference, ChannelConfig config, long maxBytes) {
        HttpResponse<String> located = get(URI.create(properties.getBaseUrl() + "/" + properties.getApiVersion()
                + "/" + URLEncoder.encode(providerReference, StandardCharsets.UTF_8)), config,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (located.statusCode() < 200 || located.statusCode() >= 300) {
            throw ChannelMediaException.ofStatus(located.statusCode(), "WhatsApp media lookup returned HTTP "
                    + located.statusCode() + ": " + describeError(located.body()));
        }
        JsonNode media;
        try {
            media = objectMapper.readTree(located.body());
        } catch (IOException e) {
            throw new ChannelMediaException("WhatsApp media lookup returned a body that is not JSON", false, e);
        }
        if (!media.path("url").isTextual()) {
            throw new ChannelMediaException("WhatsApp media lookup returned no URL");
        }
        if (media.path("file_size").canConvertToLong() && media.path("file_size").asLong() > maxBytes) {
            throw new VoiceNoteRejectedException("The voice note is " + media.path("file_size").asLong()
                    + " bytes, over the limit of " + maxBytes);
        }
        HttpResponse<byte[]> bytes = get(URI.create(media.path("url").asText()), config,
                HttpResponse.BodyHandlers.ofByteArray());
        if (bytes.statusCode() < 200 || bytes.statusCode() >= 300) {
            // A download URL lives for minutes: the next attempt looks the media up again for a new one.
            throw ChannelMediaException.ofStatus(bytes.statusCode(), "WhatsApp media download returned HTTP "
                    + bytes.statusCode());
        }
        if (bytes.body().length > maxBytes) {
            throw new VoiceNoteRejectedException("The voice note is " + bytes.body().length
                    + " bytes, over the limit of " + maxBytes);
        }
        return bytes.body();
    }

    private <T> HttpResponse<T> get(URI uri, ChannelConfig config, HttpResponse.BodyHandler<T> handler) {
        if (!config.isAppRouted()) {
            throw new ChannelMediaException("Channel config " + config.id() + " is not a WhatsApp number");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.getTimeout())
                .header("Authorization", "Bearer " + config.credential())
                .GET()
                .build();
        try {
            return httpClient.send(request, handler);
        } catch (IOException e) {
            throw new ChannelMediaException("HTTP call to the WhatsApp Cloud API for a voice note failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelMediaException("Interrupted while fetching a voice note from WhatsApp", true, e);
        }
    }

    /** Meta's error code and message, never the raw body (it is not ours to log in full). */
    private String describeError(String body) {
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            int code = error.path("code").asInt(-1);
            String hint = code == REENGAGEMENT_ERROR ? " (re-engagement: outside the 24-hour window)" : "";
            return "error " + code + hint + " — " + error.path("message").asText("(no message)");
        } catch (JacksonException e) {
            return "(unparseable error body)";
        }
    }
}
