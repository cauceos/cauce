package dev.cauce.api.channel;

import dev.cauce.channels.ChannelConfigCreationResult;
import dev.cauce.channels.ChannelConfigService;
import dev.cauce.channels.whatsapp.WhatsAppNumberService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for channel bindings. Thin: delegates to {@link ChannelConfigService};
 * tenant context is derived from the validated API key, and RLS enforces that the agent is
 * visible to the caller (out-of-scope agents surface as not-found).
 *
 * <p>Listing and disabling bindings are deferred until the operational need exists (TODO,
 * same criterion as pagination).
 */
@RestController
public class ChannelConfigController {

    private static final String WHATSAPP = "whatsapp";

    private final ChannelConfigService channelConfigService;
    private final WhatsAppNumberService whatsAppNumberService;

    public ChannelConfigController(ChannelConfigService channelConfigService,
                                   WhatsAppNumberService whatsAppNumberService) {
        this.channelConfigService = channelConfigService;
        this.whatsAppNumberService = whatsAppNumberService;
    }

    @PostMapping("/v1/agents/{agentId}/channels")
    public ResponseEntity<ChannelConfigResponse> create(
            @PathVariable UUID agentId,
            @Valid @RequestBody CreateChannelConfigRequest request) {
        // A WhatsApp number is app-routed (ADR 0006): it names the app that receives its webhooks
        // and Meta's phone_number_id, and its credential is the access token replies are sent with.
        ChannelConfigCreationResult result;
        if (WHATSAPP.equals(request.channelType())) {
            if (request.whatsappAppId() == null || request.phoneNumberId() == null
                    || request.phoneNumberId().isBlank()) {
                throw new InvalidChannelConfigRequestException(
                        "A whatsapp channel requires whatsapp_app_id and phone_number_id");
            }
            result = whatsAppNumberService.bindNumber(agentId, request.whatsappAppId(),
                    request.phoneNumberId(), request.credential());
        } else {
            result = channelConfigService.createChannelConfig(agentId, request.channelType(), request.credential());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ChannelConfigResponse.from(result));
    }

    /**
     * Replaces the webhook secret of a channel instance and returns the new one, once. The secret
     * is stored only hashed, so re-registering a provider webhook (Telegram {@code setWebhook})
     * goes through here. A config outside the caller's scope is a 404; a WhatsApp number, which
     * has no secret of its own, a 400.
     */
    @PostMapping("/v1/channel-configs/{configId}/webhook-secret")
    public ChannelConfigResponse rotateWebhookSecret(@PathVariable UUID configId) {
        return ChannelConfigResponse.from(channelConfigService.rotateWebhookSecret(configId));
    }
}
