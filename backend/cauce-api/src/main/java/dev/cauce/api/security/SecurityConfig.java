package dev.cauce.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.web.RequestIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Wires {@link ApiKeyAuthenticationFilter} into the Spring Security filter chain.
 *
 * <p>The application is fully stateless: no sessions, no CSRF (REST API), no form
 * login, no HTTP Basic. The custom filter runs before
 * {@link UsernamePasswordAuthenticationFilter} so it has a chance to populate the
 * {@code SecurityContext} before Spring's defaults take over.
 *
 * <p>Public endpoints (in v1.0):
 * <ul>
 *   <li>{@code /actuator/health} and {@code /actuator/info} — operational health.</li>
 *   <li>{@code /v1/api-docs/**} and {@code /swagger-ui/**} — placeholder for the
 *       OpenAPI surface that lands in a later commit.</li>
 * </ul>
 * Everything else requires a valid API key.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   RequestIdFilter requestIdFilter,
                                                   ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
                                                   ObjectMapper objectMapper)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/v1/api-docs/**", "/swagger-ui/**").permitAll()
                        // Everything else — the /v1 API surface and non-health actuator endpoints —
                        // requires a valid API key. ApiKeyAuthenticationFilter authenticates the
                        // Bearer key and derives the tenant context from the validated principal.
                        .anyRequest().authenticated())
                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(new Http401AuthenticationEntryPoint(objectMapper)))
                .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // request_id must be assigned before authentication so even 401 responses
                // carry it (in the MDC and the X-Request-Id header).
                .addFilterBefore(requestIdFilter, ApiKeyAuthenticationFilter.class)
                .build();
    }
}
