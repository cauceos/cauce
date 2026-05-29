package dev.cauce.api.web;

import dev.cauce.core.agent.TenantNotFoundException;
import dev.cauce.core.apikey.ApiKeyAlreadyRevokedException;
import dev.cauce.core.conversation.InvalidConversationTransitionException;
import dev.cauce.core.tenant.MissingTenantContextException;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.exception.LlmRateLimitException;
import dev.cauce.llm.exception.LlmTimeoutException;
import dev.cauce.orchestration.exception.UnknownModelException;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only HTTP surface used by {@link GlobalExceptionHandlerIT} to exercise the
 * exception-to-status mapping end-to-end before any real domain controller exists.
 * Loaded only under the {@code test} profile so it never reaches production.
 *
 * <p>One endpoint per representative HTTP status. {@code unexpected} throws a raw
 * {@link RuntimeException} whose message deliberately contains a secret marker, so the
 * test can assert the 500 body never echoes it.
 */
@RestController
@Profile("test")
public class TestExceptionEndpoints {

    /** Secret marker that must never appear in the 500 response body. */
    public static final String INTERNAL_DETAIL = "internal-stacktrace-secret-do-not-leak";

    @GetMapping("/test/throw/not-found")
    public void notFound() {
        throw new TenantNotFoundException("tenant 123 not found");
    }

    @GetMapping("/test/throw/bad-request")
    public void badRequest() {
        throw new InvalidConversationTransitionException("cannot reopen an ARCHIVED conversation");
    }

    @GetMapping("/test/throw/missing-context")
    public void missingContext() {
        throw new MissingTenantContextException("no tenant context set");
    }

    @GetMapping("/test/throw/conflict")
    public void conflict() {
        throw new ApiKeyAlreadyRevokedException("api key already revoked");
    }

    @GetMapping("/test/throw/unprocessable")
    public void unprocessable() {
        throw new UnknownModelException("unknown model: gpt-9");
    }

    @GetMapping("/test/throw/bad-gateway")
    public void badGateway() {
        throw new LlmAuthenticationException("anthropic", "claude-opus-4-8", "provider rejected the key");
    }

    @GetMapping("/test/throw/rate-limit")
    public void rateLimit() {
        throw new LlmRateLimitException("anthropic", "claude-opus-4-8", "429 from provider");
    }

    @GetMapping("/test/throw/timeout")
    public void timeout() {
        throw new LlmTimeoutException("anthropic", "claude-opus-4-8", "timed out after 30s");
    }

    @GetMapping("/test/throw/unexpected")
    public void unexpected() {
        throw new RuntimeException(INTERNAL_DETAIL);
    }
}
