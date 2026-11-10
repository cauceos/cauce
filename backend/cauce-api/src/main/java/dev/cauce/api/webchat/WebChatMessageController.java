package dev.cauce.api.webchat;

import dev.cauce.channels.webchat.ResolvedWebChat;
import dev.cauce.channels.webchat.WebChatMessageService;
import dev.cauce.orchestration.InboundMessageResult;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A session's messages over the public surface (ADR 0014, W3): {@code POST} sends one into the ingest
 * every channel uses and answers 202; {@code GET} polls the current thread after a cursor. Both take
 * the session token in {@code Authorization: Bearer}; {@link WebChatCorsFilter} has resolved the
 * channel and checked the origin.
 */
@RestController
public class WebChatMessageController {

    private final WebChatSessions sessions;
    private final WebChatMessageService messages;

    public WebChatMessageController(WebChatSessions sessions, WebChatMessageService messages) {
        this.sessions = sessions;
        this.messages = messages;
    }

    @PostMapping("/web/v1/{publicKey}/messages")
    public ResponseEntity<SendWebMessageResponse> send(
            @PathVariable String publicKey,
            @RequestAttribute(WebChatPaths.CHANNEL_ATTRIBUTE) ResolvedWebChat channel,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody SendWebMessageRequest request) {
        InboundMessageResult result = sessions.run(channel, authorization, session -> messages.send(channel, session,
                request.content(), idempotencyKey, Boolean.TRUE.equals(request.newThread())));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new SendWebMessageResponse(result.conversationId(),
                result.messageId(), result.invocationId()));
    }

    @GetMapping("/web/v1/{publicKey}/messages")
    public WebMessagePageResponse read(
            @PathVariable String publicKey,
            @RequestAttribute(WebChatPaths.CHANNEL_ATTRIBUTE) ResolvedWebChat channel,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(required = false) Integer limit) {
        return WebMessagePageResponse.from(sessions.run(channel, authorization,
                session -> messages.read(channel, session, cursor, limit)));
    }
}
