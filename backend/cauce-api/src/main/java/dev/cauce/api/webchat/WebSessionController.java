package dev.cauce.api.webchat;

import dev.cauce.channels.webchat.ResolvedWebChat;
import dev.cauce.channels.webchat.WebSessionService;
import dev.cauce.core.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /web/v1/{publicKey}/sessions} (ADR 0014, WC2): opens an anonymous session with a valid
 * Turnstile token. {@link WebChatCorsFilter} has resolved the channel and checked the origin; the
 * session is opened under the channel's tenant context, set here from the resolved row and cleared
 * after, as the webhook path does (ADR 0001).
 */
@RestController
public class WebSessionController {

    private final WebSessionService sessions;
    private final WebChatRateLimiter limiter;
    private final ClientIps ips;

    public WebSessionController(WebSessionService sessions, WebChatRateLimiter limiter, ClientIps ips) {
        this.sessions = sessions;
        this.limiter = limiter;
        this.ips = ips;
    }

    @PostMapping("/web/v1/{publicKey}/sessions")
    public ResponseEntity<OpenSessionResponse> open(@PathVariable String publicKey,
                                                    @RequestAttribute(WebChatPaths.CHANNEL_ATTRIBUTE)
                                                    ResolvedWebChat channel,
                                                    @Valid @RequestBody OpenSessionRequest request,
                                                    HttpServletRequest http) {
        // Counted before Turnstile is asked, so a flood never reaches Cloudflare.
        limiter.hit(channel.channelConfigId(), channel.limits(), WebChatRateLimiter.Bucket.SESSIONS_PER_IP,
                ips.hashOf(http));
        TenantContext.setCurrentTenantId(channel.tenantId());
        try {
            WebSessionService.Opened opened = sessions.open(channel, request.turnstileToken(), request.consent(),
                    request.noticeVersion());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new OpenSessionResponse(opened.token(), opened.session().expiresAt()));
        } finally {
            TenantContext.clear();
        }
    }
}
