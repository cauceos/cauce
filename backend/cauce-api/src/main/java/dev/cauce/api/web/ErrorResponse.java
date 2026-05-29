package dev.cauce.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
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
 *
 * <p>For input-validation failures only, an {@code errors} array lists the offending fields.
 * It is omitted entirely for every other error so the body shape is otherwise unchanged.
 */
public record ErrorResponse(
        @JsonProperty("error") String error,
        @JsonProperty("message") String message,
        @JsonProperty("request_id") String requestId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldError> errors) {

    /** A single field-level validation violation. */
    public record FieldError(String field, String message) {
    }

    /**
     * Builds a response pulling {@code request_id} from the {@link MDC} populated by
     * {@link RequestIdFilter}. Returns {@code null} for the id only if no filter ran
     * (e.g. outside a request), which never happens on the normal request path.
     */
    public static ErrorResponse of(String error, String message) {
        return of(error, message, null);
    }

    /** As {@link #of(String, String)} but with a list of field-level validation errors. */
    public static ErrorResponse of(String error, String message, List<FieldError> errors) {
        return new ErrorResponse(error, message, MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY), errors);
    }
}
