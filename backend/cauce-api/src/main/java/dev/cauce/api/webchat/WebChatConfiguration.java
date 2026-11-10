package dev.cauce.api.webchat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.channels.webchat.WebChatChannelService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the public web-chat surface's door (ADR 0014, WC1): {@link WebChatCorsFilter} on
 * {@code /web/v1/*}, ahead of Spring Security, so a preflight is answered before any authentication
 * and a disallowed origin never reaches an endpoint.
 */
@Configuration
public class WebChatConfiguration {

    @Bean
    public FilterRegistrationBean<WebChatCorsFilter> webChatCorsFilter(WebChatChannelService channels,
                                                                     ObjectMapper objectMapper,
                                                                     WebChatMetrics metrics) {
        FilterRegistrationBean<WebChatCorsFilter> registration =
                new FilterRegistrationBean<>(new WebChatCorsFilter(channels, objectMapper, metrics));
        registration.addUrlPatterns(WebChatPaths.PREFIX + "*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
