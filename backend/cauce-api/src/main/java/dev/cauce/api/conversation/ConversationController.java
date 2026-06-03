package dev.cauce.api.conversation;

import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.tenancy.ConversationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for conversation status. Thin: delegates to {@link ConversationService} and maps
 * the domain result to a {@link ConversationResponse}. Tenant context is derived from the
 * validated API key by {@code ApiKeyAuthenticationFilter}; RLS enforces visibility, so a
 * conversation outside the caller's scope is reported as not-found.
 */
@RestController
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping("/v1/conversations/{conversationId}")
    public ConversationResponse get(@PathVariable UUID conversationId) {
        return ConversationResponse.from(conversationService.getConversation(conversationId).orElseThrow(() ->
                new ConversationNotFoundException("No conversation found for id " + conversationId)));
    }
}
