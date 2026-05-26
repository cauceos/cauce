package dev.cauce.api.security;

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
                                                   ApiKeyAuthenticationFilter apiKeyAuthenticationFilter)
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
                        .anyRequest().authenticated())
                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(new Http401AuthenticationEntryPoint()))
                .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
