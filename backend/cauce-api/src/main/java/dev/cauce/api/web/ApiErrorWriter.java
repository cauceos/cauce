package dev.cauce.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Serialises an {@link ErrorResponse} straight onto an {@link HttpServletResponse}.
 *
 * <p>Used by the security components (the authentication filter and entry point) that run
 * <em>outside</em> Spring MVC and therefore cannot return a {@code ResponseEntity} for the
 * message converters to render. Controller-thrown exceptions go through
 * {@code GlobalExceptionHandler} instead, which produces the same body shape. Centralising
 * the write here keeps every error — MVC or filter — on one wire format.
 */
public final class ApiErrorWriter {

    private ApiErrorWriter() {
    }

    public static void write(HttpServletResponse response, HttpStatus status,
                             String error, String message, ObjectMapper objectMapper) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ErrorResponse.of(error, message)));
    }
}
