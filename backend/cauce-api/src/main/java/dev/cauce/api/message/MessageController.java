package dev.cauce.api.message;

import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.orchestration.InboundMessageResult;
import dev.cauce.orchestration.InboundMessageService;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.MessageService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for messages — the public trigger that closes the loop with the invocation engine.
 * Thin: posting delegates to {@link InboundMessageService#ingest} (resolve-or-create conversation,
 * append the USER message, enqueue the invocation, atomically); reading delegates to
 * {@link MessageService}. Tenant context is derived from the validated API key by
 * {@code ApiKeyAuthenticationFilter}, and RLS enforces visibility — an agent or conversation the
 * caller cannot see is reported as not-found.
 *
 * <p>Posting is asynchronous: it returns {@code 202 Accepted} once the message is queued; the
 * agent's reply is produced by the worker and the client polls
 * {@code GET /v1/conversations/{id}/messages} for it.
 */
@RestController
public class MessageController {

    /** Reserved built-in channel for the REST messaging endpoint (see ConversationService). */
    private static final String API_CHANNEL = "api";

    private final InboundMessageService inboundMessageService;
    private final ConversationService conversationService;
    private final MessageService messageService;

    public MessageController(InboundMessageService inboundMessageService,
                             ConversationService conversationService,
                             MessageService messageService) {
        this.inboundMessageService = inboundMessageService;
        this.conversationService = conversationService;
        this.messageService = messageService;
    }

    @PostMapping("/v1/agents/{agentId}/messages")
    public ResponseEntity<PostMessageResponse> post(@PathVariable UUID agentId,
                                                    @Valid @RequestBody PostMessageRequest request) {
        InboundMessageResult result = inboundMessageService.ingest(
                agentId, API_CHANNEL, request.externalIdentityRef(), request.content());
        return ResponseEntity.accepted().body(PostMessageResponse.from(result));
    }

    // TODO: paginate once conversations can grow long; returns the full thread for now.
    @GetMapping("/v1/conversations/{conversationId}/messages")
    public List<MessageResponse> list(@PathVariable UUID conversationId) {
        // listMessages does not itself distinguish an empty thread from an invisible one, so
        // probe visibility first to return 404 for a conversation outside the caller's scope.
        conversationService.getConversation(conversationId).orElseThrow(() ->
                new ConversationNotFoundException("No conversation found for id " + conversationId));
        return messageService.listMessages(conversationId).stream().map(MessageResponse::from).toList();
    }
}
