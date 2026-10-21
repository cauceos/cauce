package dev.cauce.channels.whatsapp;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelDeliveryException;
import dev.cauce.channels.spi.ChannelOutboundMessage;
import dev.cauce.channels.spi.OutboundChannelAdapter;
import dev.cauce.core.identity.IdentityKind;
import java.io.IOException;
import java.net.URI;
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
 */
public class WhatsAppChannelAdapter implements OutboundChannelAdapter {

    static final String CHANNEL_TYPE = "whatsapp";
    static final int MAX_TEXT_LENGTH = 4096;
    static final Duration SERVICE_WINDOW = Duration.ofHours(24);

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
                new ChannelDeliveryException("The 24-hour window cannot be checked: no message from the interlocutor"));
        if (Duration.between(lastInbound, clock.instant()).compareTo(SERVICE_WINDOW) > 0) {
            throw new ChannelDeliveryException("Outside the WhatsApp 24-hour customer service window "
                    + "(last interlocutor message at " + lastInbound + "); templates are not supported yet");
        }
        if (message.content().length() > MAX_TEXT_LENGTH) {
            throw new ChannelDeliveryException("Reply of " + message.content().length()
                    + " characters exceeds the WhatsApp text limit of " + MAX_TEXT_LENGTH + "; it is not split");
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("messaging_product", "whatsapp");
        payload.put("recipient_type", "individual");
        payload.put("recipient", message.identityValue());
        payload.put("type", "text");
        ObjectNode text = payload.putObject("text");
        text.put("body", message.content());
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
            throw new ChannelDeliveryException("WhatsApp send returned HTTP " + response.statusCode() + ": "
                    + describeError(response.body()));
        }
    }

    /** Meta's error code and message, never the raw body (it is not ours to log in full). */
    private String describeError(String body) {
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            int code = error.path("code").asInt(-1);
            String hint = code == 131047 ? " (re-engagement: outside the 24-hour window)" : "";
            return "error " + code + hint + " — " + error.path("message").asText("(no message)");
        } catch (JacksonException e) {
            return "(unparseable error body)";
        }
    }
}
