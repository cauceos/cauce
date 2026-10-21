package dev.cauce.api.conversation;

import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.identity.Identity;
import dev.cauce.tenancy.ConversationService;
import dev.cauce.tenancy.IdentityService;
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
    private final IdentityService identityService;

    public ConversationController(ConversationService conversationService, IdentityService identityService) {
        this.conversationService = conversationService;
        this.identityService = identityService;
    }

    @GetMapping("/v1/conversations/{conversationId}")
    public ConversationResponse get(@PathVariable UUID conversationId) {
        Conversation conversation = conversationService.getConversation(conversationId).orElseThrow(() ->
                new ConversationNotFoundException("No conversation found for id " + conversationId));
        // The identity belongs to the agent's tenant, so it is visible whenever the
        // conversation is (both follow the agent): a miss is a broken invariant, not a 404.
        Identity identity = identityService.getIdentity(conversation.identityId()).orElseThrow(() ->
                new IllegalStateException("Identity of visible conversation " + conversationId + " is not visible"));
        return ConversationResponse.from(conversation, identity);
    }
}
