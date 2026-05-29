package dev.cauce.api.web;

import dev.cauce.core.UuidGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns a unique {@code request_id} to every inbound HTTP request and makes it
 * available for the whole request lifecycle.
 *
 * <p>The id is a time-ordered {@link UuidGenerator#newV7() UUIDv7}, so values sort by
 * creation time, which is convenient when scanning logs. It is exposed three ways:
 * <ul>
 *   <li>the SLF4J {@link MDC} under {@link #REQUEST_ID_MDC_KEY}, so every log line emitted
 *       while handling the request carries it (the logging correlation pattern includes
 *       {@code %X{requestId}});</li>
 *   <li>a request attribute ({@link #REQUEST_ID_ATTRIBUTE}) for in-process access;</li>
 *   <li>the {@link #REQUEST_ID_HEADER X-Request-Id} response header, so clients can quote
 *       it when reporting a problem.</li>
 * </ul>
 *
 * <p>This filter is registered <strong>before</strong> the authentication filter (see
 * {@code SecurityConfig}) so even {@code 401} responses — written directly by the auth
 * filter or the authentication entry point — already carry the id in the MDC and the
 * response header.
 *
 * <p>The MDC entry is removed in a {@code finally} block so the worker thread does not
 * leak the id into the next request it serves.
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_MDC_KEY = "requestId";
    public static final String REQUEST_ID_ATTRIBUTE = "requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = UuidGenerator.newV7().toString();
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }
}
