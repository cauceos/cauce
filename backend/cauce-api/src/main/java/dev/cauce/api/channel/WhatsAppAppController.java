package dev.cauce.api.channel;

import dev.cauce.channels.whatsapp.app.WhatsAppAppNotFoundException;
import dev.cauce.channels.whatsapp.app.WhatsAppAppService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * WhatsApp apps (ADR 0006, W2). Registering returns the webhook path and, once, the verify token
 * to configure in Meta; an app outside the caller's scope is a 404.
 */
@RestController
public class WhatsAppAppController {

    private final WhatsAppAppService whatsAppAppService;

    public WhatsAppAppController(WhatsAppAppService whatsAppAppService) {
        this.whatsAppAppService = whatsAppAppService;
    }

    @PostMapping("/v1/tenants/{tenantId}/whatsapp-apps")
    public ResponseEntity<WhatsAppAppResponse> register(@PathVariable UUID tenantId,
                                                        @Valid @RequestBody RegisterWhatsAppAppRequest request) {
        var registration = whatsAppAppService.registerApp(tenantId, request.metaAppId(), request.appSecret());
        return ResponseEntity.created(URI.create("/v1/whatsapp-apps/" + registration.app().id()))
                .body(WhatsAppAppResponse.from(registration));
    }

    @GetMapping("/v1/whatsapp-apps/{appId}")
    public WhatsAppAppResponse get(@PathVariable UUID appId) {
        return WhatsAppAppResponse.from(whatsAppAppService.getApp(appId).orElseThrow(() ->
                new WhatsAppAppNotFoundException("No WhatsApp app found for id " + appId)));
    }
}
