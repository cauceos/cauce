package dev.cauce.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Renders authentication failures as a 401 with a small JSON body, instead of the
 * default Spring Security behaviour (302 to a non-existent login page, or 403). The
 * filter chain delegates here whenever a protected endpoint is hit without a valid
 * authentication.
 */
public class Http401AuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String BODY = "{\"error\":\"unauthorized\"}";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(BODY);
    }
}
