package dev.cauce.api.web;

import dev.cauce.core.agent.AgentNotFoundException;
import dev.cauce.core.apikey.ApiKeyAlreadyRevokedException;
import dev.cauce.core.apikey.ApiKeyNotFoundException;
import dev.cauce.core.conversation.ConversationNotFoundException;
import dev.cauce.core.conversation.InvalidChannelTypeException;
import dev.cauce.core.conversation.InvalidConversationTransitionException;
import dev.cauce.core.message.MessageNotFoundException;
import dev.cauce.core.tenant.InvalidTenantTierException;
import dev.cauce.core.tenant.MissingTenantContextException;
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.exception.LlmInvalidRequestException;
import dev.cauce.llm.exception.LlmRateLimitException;
import dev.cauce.llm.exception.LlmTimeoutException;
import dev.cauce.orchestration.PendingInvocationNotFoundException;
import dev.cauce.orchestration.exception.InvalidPendingInvocationTransitionException;
import dev.cauce.orchestration.exception.InvalidTriggerMessageException;
import dev.cauce.orchestration.exception.LlmProviderNotAvailableException;
import dev.cauce.orchestration.exception.MaxRetriesExceededException;
import dev.cauce.orchestration.exception.MessageTooLargeForContextException;
import dev.cauce.orchestration.exception.UnknownModelException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Translates exceptions thrown by controllers into the uniform {@link ErrorResponse}
 * body, with a consistent HTTP status and a stable, machine-readable {@code error} code.
 *
 * <p><strong>Error-code contract.</strong> The {@code error} string is part of the public
 * API contract and is deliberately <em>decoupled</em> from the Java class name: renaming an
 * exception must never silently change the wire contract. The single source of truth is the
 * {@link #ERROR_CODES} table below — one place to audit every exception → code → status.
 *
 * <p><strong>Information disclosure.</strong> For client errors (4xx) the exception message
 * is safe to return. For server errors (5xx) the body carries a generic message and the full
 * detail (including stack trace) is logged at {@code ERROR} only — never returned — so we do
 * not leak internals (stack traces, provider/model identifiers, SQL, …) to callers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String FALLBACK_CODE = "internal_error";
    private static final String GENERIC_500_MESSAGE = "An unexpected error occurred";
    private static final String GENERIC_502_MESSAGE = "The upstream LLM provider returned an error";
    private static final String GENERIC_503_MESSAGE = "The upstream LLM provider is rate limiting requests";
    private static final String GENERIC_504_MESSAGE = "The upstream LLM provider timed out";

    /** Default backoff advertised on a 503 when the provider does not tell us how long to wait. */
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 30;

    /**
     * Authoritative exception → public error code map. Keep this table aligned with the
     * {@code @ExceptionHandler} groupings below; the HTTP status lives with the handler,
     * the wire code lives here.
     */
    private static final Map<Class<? extends Throwable>, String> ERROR_CODES = Map.ofEntries(
            // 404 NOT_FOUND
            Map.entry(TenantNotFoundException.class, "tenant_not_found"),
            Map.entry(AgentNotFoundException.class, "agent_not_found"),
            Map.entry(ConversationNotFoundException.class, "conversation_not_found"),
            Map.entry(MessageNotFoundException.class, "message_not_found"),
            Map.entry(ApiKeyNotFoundException.class, "api_key_not_found"),
            Map.entry(PendingInvocationNotFoundException.class, "pending_invocation_not_found"),
            // 400 BAD_REQUEST
            Map.entry(InvalidConversationTransitionException.class, "invalid_conversation_transition"),
            Map.entry(InvalidChannelTypeException.class, "invalid_channel_type"),
            Map.entry(InvalidTriggerMessageException.class, "invalid_trigger_message"),
            // 401 UNAUTHORIZED
            Map.entry(MissingTenantContextException.class, "missing_tenant_context"),
            // 409 CONFLICT
            Map.entry(ApiKeyAlreadyRevokedException.class, "api_key_already_revoked"),
            Map.entry(MaxRetriesExceededException.class, "max_retries_exceeded"),
            Map.entry(InvalidPendingInvocationTransitionException.class, "invalid_pending_invocation_transition"),
            // 422 UNPROCESSABLE_ENTITY
            Map.entry(InvalidTenantTierException.class, "invalid_tenant_tier"),
            Map.entry(MessageTooLargeForContextException.class, "message_too_large_for_context"),
            Map.entry(UnknownModelException.class, "unknown_model"),
            // 502 BAD_GATEWAY
            Map.entry(LlmAuthenticationException.class, "llm_authentication_error"),
            Map.entry(LlmProviderNotAvailableException.class, "llm_provider_not_available"),
            Map.entry(LlmInvalidRequestException.class, "llm_invalid_request"),
            // 503 SERVICE_UNAVAILABLE
            Map.entry(LlmRateLimitException.class, "llm_rate_limit"),
            // 504 GATEWAY_TIMEOUT
            Map.entry(LlmTimeoutException.class, "llm_timeout"));

    // === 4xx — client errors: safe to echo the exception message ===

    @ExceptionHandler({
            TenantNotFoundException.class,
            AgentNotFoundException.class,
            ConversationNotFoundException.class,
            MessageNotFoundException.class,
            ApiKeyNotFoundException.class,
            PendingInvocationNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(RuntimeException ex) {
        return clientError(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler({
            InvalidConversationTransitionException.class,
            InvalidChannelTypeException.class,
            InvalidTriggerMessageException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException ex) {
        return clientError(HttpStatus.BAD_REQUEST, ex);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ErrorResponse.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        log.warn("validation_failed [{}]: {} field error(s)", HttpStatus.BAD_REQUEST.value(), fields.size());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("validation_failed", "Request validation failed", fields));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("invalid_path_parameter [{}]: {}", HttpStatus.BAD_REQUEST.value(), ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("invalid_path_parameter", "Invalid value for parameter '" + ex.getName() + "'"));
    }

    @ExceptionHandler(MissingTenantContextException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(RuntimeException ex) {
        return clientError(HttpStatus.UNAUTHORIZED, ex);
    }

    @ExceptionHandler({
            ApiKeyAlreadyRevokedException.class,
            MaxRetriesExceededException.class,
            InvalidPendingInvocationTransitionException.class})
    public ResponseEntity<ErrorResponse> handleConflict(RuntimeException ex) {
        return clientError(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler({
            InvalidTenantTierException.class,
            MessageTooLargeForContextException.class,
            UnknownModelException.class})
    public ResponseEntity<ErrorResponse> handleUnprocessable(RuntimeException ex) {
        return clientError(HttpStatus.UNPROCESSABLE_ENTITY, ex);
    }

    // === 5xx — server/upstream errors: generic body, detail only in logs ===

    @ExceptionHandler({
            LlmAuthenticationException.class,
            LlmProviderNotAvailableException.class,
            LlmInvalidRequestException.class})
    public ResponseEntity<ErrorResponse> handleBadGateway(RuntimeException ex) {
        return serverError(HttpStatus.BAD_GATEWAY, ex, GENERIC_502_MESSAGE);
    }

    @ExceptionHandler(LlmRateLimitException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(LlmRateLimitException ex) {
        log.error("{} [{}]: {}", codeFor(ex), HttpStatus.SERVICE_UNAVAILABLE.value(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(DEFAULT_RETRY_AFTER_SECONDS))
                .body(ErrorResponse.of(codeFor(ex), GENERIC_503_MESSAGE));
    }

    @ExceptionHandler(LlmTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleGatewayTimeout(LlmTimeoutException ex) {
        return serverError(HttpStatus.GATEWAY_TIMEOUT, ex, GENERIC_504_MESSAGE);
    }

    // Fallback for argument-shaped errors surfacing from the service layer (e.g. an
    // unsupported value a DTO does not yet constrain, like an unknown model provider).
    // Matches only IllegalArgumentException, so it never shadows the specific domain
    // handlers above; anything else still falls through to the 500 handler.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("bad_request [{}]: {}", HttpStatus.BAD_REQUEST.value(), ex.getMessage());
        return ResponseEntity.badRequest().body(ErrorResponse.of("bad_request", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        // This is our bug, not the caller's: keep the full detail in the logs, return nothing
        // revealing in the body.
        log.error("{} [{}]: unhandled exception", FALLBACK_CODE, HttpStatus.INTERNAL_SERVER_ERROR.value(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(FALLBACK_CODE, GENERIC_500_MESSAGE));
    }

    private ResponseEntity<ErrorResponse> clientError(HttpStatus status, RuntimeException ex) {
        String code = codeFor(ex);
        log.warn("{} [{}]: {}", code, status.value(), ex.getMessage());
        return ResponseEntity.status(status).body(ErrorResponse.of(code, ex.getMessage()));
    }

    private ResponseEntity<ErrorResponse> serverError(HttpStatus status, RuntimeException ex, String publicMessage) {
        String code = codeFor(ex);
        log.error("{} [{}]: {}", code, status.value(), ex.getMessage(), ex);
        return ResponseEntity.status(status).body(ErrorResponse.of(code, publicMessage));
    }

    private static String codeFor(Throwable ex) {
        return ERROR_CODES.getOrDefault(ex.getClass(), FALLBACK_CODE);
    }
}
