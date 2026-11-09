package dev.cauce.api.webchat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.web.ErrorResponse;
import dev.cauce.api.web.RequestIdFilter;
import dev.cauce.channels.webchat.ResolvedWebChat;
import dev.cauce.channels.webchat.WebChatChannelService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The door of the public web-chat surface, {@code /web/v1/{publicKey}/...} (ADR 0014, WC1). It
 * resolves the channel by the key in the path, through the {@code SECURITY DEFINER} lookup, and checks
 * the request's {@code Origin} against that channel's allowed origins, exactly:
 * <ul>
 *   <li>an unknown or disabled key, or a disallowed origin: 403, with no detail and no CORS headers,
 *       alike, so a page cannot tell one from the other;</li>
 *   <li>an allowed origin: the response names it in {@code Access-Control-Allow-Origin}; a preflight
 *       is answered here, 204, with the methods and headers the surface takes;</li>
 *   <li>no {@code Origin} at all: not a browser, not a CORS matter; the endpoint's own checks apply.</li>
 * </ul>
 * There are no cookies and no credentials: {@code Access-Control-Allow-Credentials} is never sent. The
 * resolved channel goes on as a request attribute. Registered by {@code WebChatConfiguration} on
 * {@code /web/v1/*}, ahead of Spring Security.
 */
public class WebChatCorsFilter extends OncePerRequestFilter {

    static final String ALLOWED_METHODS = "GET, POST";
    static final String ALLOWED_HEADERS = "Authorization, Content-Type, Idempotency-Key";
    static final String MAX_AGE_SECONDS = "600";

    private final WebChatChannelService channels;
    private final ObjectMapper objectMapper;

    public WebChatCorsFilter(WebChatChannelService channels, ObjectMapper objectMapper) {
        this.channels = channels;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(WebChatPaths.PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<ResolvedWebChat> channel = WebChatPaths.publicKeyOf(request.getRequestURI()).flatMap(channels::resolve);
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (channel.isEmpty() || origin != null && !channel.get().allows(origin)) {
            forbidden(response);
            return;
        }
        if (origin != null) {
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
            if (HttpMethod.OPTIONS.matches(request.getMethod())
                    && request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null) {
                response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, ALLOWED_METHODS);
                response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, ALLOWED_HEADERS);
                response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, MAX_AGE_SECONDS);
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                return;
            }
        }
        request.setAttribute(WebChatPaths.CHANNEL_ATTRIBUTE, channel.get());
        chain.doFilter(request, response);
    }

    private void forbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                new ErrorResponse("forbidden", "Forbidden", MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY), null));
    }
}
