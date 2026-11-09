package dev.cauce.api.webchat;

import dev.cauce.channels.webchat.ResolvedWebChat;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /web/v1/{publicKey}/config}: the widget's public configuration, read anonymously at page
 * load (ADR 0014, amendment): the one request before the person's first message. Cacheable for five
 * minutes; it names no visitor. {@link WebChatCorsFilter} has resolved the channel and checked the
 * origin.
 */
@RestController
public class WebChatConfigController {

    static final Duration MAX_AGE = Duration.ofMinutes(5);

    @GetMapping("/web/v1/{publicKey}/config")
    public ResponseEntity<WebChatConfigResponse> config(@PathVariable String publicKey,
                                                        @RequestAttribute(WebChatPaths.CHANNEL_ATTRIBUTE)
                                                        ResolvedWebChat channel) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(MAX_AGE).cachePublic())
                .body(WebChatConfigResponse.from(channel));
    }
}
