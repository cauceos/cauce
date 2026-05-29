package dev.cauce.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.MDC;

/**
 * Uniform error body for the whole REST surface:
 * <pre>
 *   { "error": "&lt;code&gt;", "message": "&lt;human msg&gt;", "request_id": "&lt;uuid&gt;" }
 * </pre>
 *
 * <p>{@code error} is a stable, machine-readable code (see {@code GlobalExceptionHandler}),
 * decoupled from any Java class name. {@code request_id} correlates the response with the
 * server logs for the same request.
 */
public record ErrorResponse(
        @JsonProperty("error") String error,
        @JsonProperty("message") String message,
        @JsonProperty("request_id") String requestId) {

    /**
     * Builds a response pulling {@code request_id} from the {@link MDC} populated by
     * {@link RequestIdFilter}. Returns {@code null} for the id only if no filter ran
     * (e.g. outside a request), which never happens on the normal request path.
     */
    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY));
    }
}
