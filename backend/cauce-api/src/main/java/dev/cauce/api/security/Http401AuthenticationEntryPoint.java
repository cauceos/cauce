package dev.cauce.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.web.ApiErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Renders authentication failures as a 401 carrying the uniform {@code ErrorResponse}
 * body (the same shape {@code GlobalExceptionHandler} produces), instead of the default
 * Spring Security behaviour (302 to a non-existent login page, or 403). The filter chain
 * delegates here whenever a protected endpoint is hit without a valid authentication.
 *
 * <p>The message is intentionally generic and identical for every authentication failure.
 * We never reveal <em>why</em> a credential was rejected (unknown / revoked / expired /
 * malformed): distinguishing them would let a caller probe which keys exist. This mirrors
 * the not-distinguishing policy already applied at the lookup layer.
 */
public class Http401AuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String ERROR_CODE = "unauthorized";
    static final String MESSAGE = "Valid API key required";

    private final ObjectMapper objectMapper;

    public Http401AuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        ApiErrorWriter.write(response, HttpStatus.UNAUTHORIZED, ERROR_CODE, MESSAGE, objectMapper);
    }
}
