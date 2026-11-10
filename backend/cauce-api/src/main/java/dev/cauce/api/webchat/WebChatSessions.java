package dev.cauce.api.webchat;

import dev.cauce.channels.webchat.InvalidSessionException;
import dev.cauce.channels.webchat.ResolvedWebChat;
import dev.cauce.channels.webchat.WebSession;
import dev.cauce.channels.webchat.WebSessionService;
import dev.cauce.core.tenant.TenantContext;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Runs a session's request (ADR 0014, WC2): authenticates its {@code Bearer} token on the resolved
 * channel before any tenant context exists, then, in the session's tenant context, slides its expiry
 * and runs the work; the context is cleared after, as on the webhook path (ADR 0001).
 */
@Component
class WebChatSessions {

    private static final String BEARER = "Bearer ";

    private final WebSessionService sessions;

    WebChatSessions(WebSessionService sessions) {
        this.sessions = sessions;
    }

    <T> T run(ResolvedWebChat channel, String authorization, Function<WebSession, T> work) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            throw new InvalidSessionException("A session token is required");
        }
        WebSession session = sessions.authenticate(channel, authorization.substring(BEARER.length()))
                .orElseThrow(() -> new InvalidSessionException("The session is unknown or has expired"));
        TenantContext.setCurrentTenantId(session.tenantId());
        try {
            sessions.slide(session.id());
            return work.apply(session);
        } finally {
            TenantContext.clear();
        }
    }
}
