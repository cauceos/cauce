package dev.cauce.api.channel;

import dev.cauce.channels.config.ChannelConfigNotFoundException;
import dev.cauce.channels.webchat.WebChatChannelService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creating and reading web-chat channels (ADR 0014, W1), under API-key auth like every binding. The
 * agent's tenant must have a spend limit (409 {@code spend_limit_required} otherwise); an agent or a
 * channel outside the caller's scope is a 404.
 */
@RestController
public class WebChatChannelController {

    private final WebChatChannelService service;

    public WebChatChannelController(WebChatChannelService service) {
        this.service = service;
    }

    @PostMapping("/v1/agents/{agentId}/web-chat-channels")
    public ResponseEntity<WebChatChannelResponse> create(@PathVariable UUID agentId,
                                                         @Valid @RequestBody CreateWebChatChannelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(WebChatChannelResponse.from(service.create(agentId,
                new WebChatChannelService.Creation(request.turnstileSecret(), request.turnstileSiteKey(),
                        request.allowedOrigins(), request.privacyPolicyUrl(), request.consentMode()))));
    }

    @GetMapping("/v1/web-chat-channels/{id}")
    public WebChatChannelResponse get(@PathVariable UUID id) {
        return WebChatChannelResponse.from(service.find(id).orElseThrow(() ->
                new ChannelConfigNotFoundException("No web chat channel found for id " + id)));
    }
}
