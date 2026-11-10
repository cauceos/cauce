package dev.cauce.api.webchat;

import dev.cauce.channels.webchat.InvalidWebMessageException;
import dev.cauce.channels.webchat.ResolvedWebChat;
import dev.cauce.channels.webchat.WebChatEventService;
import dev.cauce.core.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the shop's page reports to the chat (ADR 0014, WC8). After the first message, with the session:
 * an add to cart from a card (204) and a purchase (200, saying whether it was recorded and attributed).
 * Before any message, with no session at all: an opening for the anonymous counter (204), counted only
 * when the channel turns it on, and silently ignored otherwise.
 */
@RestController
public class WebChatEventController {

    private final WebChatSessions sessions;
    private final WebChatEventService events;
    private final WebChatRateLimiter limiter;
    private final ClientIps ips;
    private final WebChatMetrics metrics;

    public WebChatEventController(WebChatSessions sessions, WebChatEventService events, WebChatRateLimiter limiter,
                                  ClientIps ips, WebChatMetrics metrics) {
        this.sessions = sessions;
        this.events = events;
        this.limiter = limiter;
        this.ips = ips;
        this.metrics = metrics;
    }

    @PostMapping("/web/v1/{publicKey}/events")
    public ResponseEntity<Map<String, Boolean>> report(
            @PathVariable String publicKey,
            @RequestAttribute(WebChatPaths.CHANNEL_ATTRIBUTE) ResolvedWebChat channel,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody WebEventRequest request) {
        String variant = metrics.variantOf(channel, request.variant());
        return sessions.run(channel, authorization, session -> {
            limiter.hit(channel.channelConfigId(), channel.limits(), WebChatRateLimiter.Bucket.EVENTS_PER_SESSION_MINUTE,
                    session.id().toString());
            if (WebEventRequest.ADD_TO_CART.equals(request.type())) {
                events.addToCart(channel, session, variant, request.ref(), request.quantity());
                metrics.cartAdd(channel, variant);
                return ResponseEntity.noContent().build();
            }
            if (WebEventRequest.PURCHASE.equals(request.type())) {
                WebChatEventService.Purchase purchase = events.purchase(channel, session, variant, request.orderId(),
                        request.value());
                if (purchase.recorded() && purchase.attributed()) {
                    metrics.purchase(channel, variant, request.value());
                }
                return ResponseEntity.ok(Map.of("recorded", purchase.recorded(), "attributed", purchase.attributed()));
            }
            throw new InvalidWebMessageException("type is add_to_cart or purchase");
        });
    }

    @PostMapping("/web/v1/{publicKey}/openings")
    public ResponseEntity<Void> opening(@PathVariable String publicKey,
                                        @RequestAttribute(WebChatPaths.CHANNEL_ATTRIBUTE) ResolvedWebChat channel,
                                        @RequestBody OpeningRequest request, HttpServletRequest http) {
        limiter.hit(channel.channelConfigId(), channel.limits(), WebChatRateLimiter.Bucket.OPENINGS_PER_IP_MINUTE,
                ips.hashOf(http));
        String variant = metrics.variantOf(channel, request.variant());
        TenantContext.setCurrentTenantId(channel.tenantId());
        try {
            if (events.countOpening(channel, variant, request.kind(), request.signal())) {
                metrics.openingCounted(channel, variant, request.kind(), request.signal());
            }
        } finally {
            TenantContext.clear();
        }
        return ResponseEntity.noContent().build();
    }
}
