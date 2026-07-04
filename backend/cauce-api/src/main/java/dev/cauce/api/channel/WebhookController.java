package dev.cauce.api.channel;

import dev.cauce.channels.ChannelWebhookService;
import dev.cauce.channels.spi.WebhookRequest;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The generic provider webhook endpoint: one URL shape for every channel,
 * {@code POST /webhooks/channels/{configId}}, dispatched through the channel SPI. Thin on
 * purpose — the whole flow (resolve config, authenticate, normalize, ingest) lives in
 * {@link ChannelWebhookService} so cauce-channels stays web-free and the web stays here.
 *
 * <p>Outside {@code /v1} and outside API-key auth ({@code SecurityConfig} permits
 * {@code /webhooks/**}): the caller is the provider, not a tenant, and authentication is
 * the channel secret verified by the adapter. The response body is always empty — the
 * provider only needs a 2xx to stop redelivering, and ingest ids are none of its business.
 */
@RestController
public class WebhookController {

    private final ChannelWebhookService channelWebhookService;

    public WebhookController(ChannelWebhookService channelWebhookService) {
        this.channelWebhookService = channelWebhookService;
    }

    @PostMapping("/webhooks/channels/{configId}")
    public ResponseEntity<Void> receive(@PathVariable UUID configId,
                                        @RequestHeader HttpHeaders headers,
                                        @RequestBody String body) {
        channelWebhookService.handle(configId, new WebhookRequest(headers.toSingleValueMap(), body));
        return ResponseEntity.ok().build();
    }
}
