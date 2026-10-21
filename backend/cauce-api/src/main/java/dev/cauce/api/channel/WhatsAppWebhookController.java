package dev.cauce.api.channel;

import dev.cauce.channels.spi.WebhookRequest;
import dev.cauce.channels.whatsapp.WhatsAppWebhookService;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The WhatsApp app webhook (ADR 0006): outside {@code /v1} and outside API-key auth, like every
 * provider webhook. The GET answers Meta's subscription check with the raw challenge; the POST
 * is authenticated by its {@code X-Hub-Signature-256} against the app's secret, over the body as
 * received — which is why it is bound as bytes.
 */
@RestController
public class WhatsAppWebhookController {

    private final WhatsAppWebhookService webhookService;

    public WhatsAppWebhookController(WhatsAppWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @GetMapping(value = "/webhooks/whatsapp/{appId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String verify(@PathVariable UUID appId,
                         @RequestParam(name = "hub.mode", required = false) String mode,
                         @RequestParam(name = "hub.verify_token", required = false) String verifyToken,
                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        return webhookService.verifySubscription(appId, mode, verifyToken, challenge);
    }

    @PostMapping("/webhooks/whatsapp/{appId}")
    public ResponseEntity<Void> receive(@PathVariable UUID appId,
                                        @RequestHeader HttpHeaders headers,
                                        @RequestBody byte[] body) {
        webhookService.handle(appId, new WebhookRequest(headers.toSingleValueMap(), body));
        return ResponseEntity.ok().build();
    }
}
