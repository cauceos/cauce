package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cauce.channels.webchat.TurnstileVerifier;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The web chat's request limits of ADR 0014, WC3, end to end against Redis: per IP for sessions, per
 * session and per IP for messages, per session for polls; a channel may only lower them; past a limit
 * the answer is 429 with how long to wait, before Turnstile or the ingest is reached; the visitor's IP
 * comes from CF-Connecting-IP only through a trusted proxy, and only its HMAC reaches Redis.
 */
@TestPropertySource(properties = "cauce.web.trusted-proxies=127.0.0.1")
class WebChatRateLimitsApiIT extends AbstractBusinessApiIT {

    private static final String SHOP = "https://tienda.example.com";
    private static final String IP_A = "203.0.113.7";
    private static final String IP_B = "198.51.100.9";

    @MockitoBean
    private TurnstileVerifier turnstile;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void limitAndTurnstile() throws Exception {
        when(turnstile.verify(anyString(), eq("token-bueno"))).thenReturn(true);
        putJson(partnerAuth, "/v1/tenants/" + clientId + "/spend-limit", """
                {"monthly_limit_usd": 50}
                """).andExpect(status().isOk());
    }

    @Test
    void sessionsPerIp_areLimitedBeforeTurnstile_andCfConnectingIpCountsThroughTheTrustedProxy() throws Exception {
        String key = createChannel("{\"sessions_per_ip_hour\": 2}");

        open(key, IP_A).andExpect(status().isCreated());
        open(key, IP_A).andExpect(status().isCreated());
        open(key, IP_A).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("rate_limited"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        open(key, IP_B).andExpect(status().isCreated());

        verify(turnstile, times(3)).verify(anyString(), eq("token-bueno"));
        Set<String> keys = redis.keys("cauce:web:rl:*");
        assertThat(keys).isNotEmpty().noneMatch(k -> k.contains(IP_A) || k.contains(IP_B));
    }

    @Test
    void messagesPerSession_andPollsPerSession_areLimited_andRefusedBeforeTheIngest() throws Exception {
        String key = createChannel("{\"messages_per_session_minute\": 2, \"reads_per_session_minute\": 3}");
        String token = json(open(key, IP_A).andExpect(status().isCreated())).get("session_token").asText();

        send(key, token).andExpect(status().isAccepted());
        send(key, token).andExpect(status().isAccepted());
        send(key, token).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("rate_limited"));
        assertThat(new org.springframework.jdbc.core.JdbcTemplate(adminDataSource)
                .queryForObject("SELECT count(*) FROM messages WHERE role = 'USER'", Integer.class)).isEqualTo(2);

        for (int i = 0; i < 3; i++) {
            read(key, token).andExpect(status().isOk());
        }
        read(key, token).andExpect(status().isTooManyRequests());
        // Another session of the same person's browser has its own buckets.
        String other = json(open(key, IP_A).andExpect(status().isCreated())).get("session_token").asText();
        send(key, other).andExpect(status().isAccepted());
    }

    @Test
    void aChannelMayOnlyLowerALimit_neverRaiseIt() throws Exception {
        UUID agentId = createAgent(clientId, clientAuth, List.of());
        postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", body("{\"messages_per_session_minute\": 11}"))
                .andExpect(status().isBadRequest());
        postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", body("{\"sessions_per_ip_hour\": 0}"))
                .andExpect(status().isBadRequest());
        postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", body("{\"messages_per_session_day\": 40}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.limits.messages_per_session_day").value(40));
    }

    private ResultActions open(String key, String ip) throws Exception {
        return mockMvc.perform(post("/web/v1/" + key + "/sessions").header(HttpHeaders.ORIGIN, SHOP)
                .header("CF-Connecting-IP", ip)
                .contentType(MediaType.APPLICATION_JSON).content("{\"turnstile_token\": \"token-bueno\"}"));
    }

    private ResultActions send(String key, String token) throws Exception {
        return mockMvc.perform(post("/web/v1/" + key + "/messages").header(HttpHeaders.ORIGIN, SHOP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("CF-Connecting-IP", IP_A)
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\": \"hola\"}"));
    }

    private ResultActions read(String key, String token) throws Exception {
        return mockMvc.perform(get("/web/v1/" + key + "/messages").header(HttpHeaders.ORIGIN, SHOP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private String createChannel(String limits) throws Exception {
        UUID agentId = createAgent(clientId, clientAuth, List.of());
        return json(postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", body(limits))
                .andExpect(status().isCreated())).get("public_key").asText();
    }

    private static String body(String limits) {
        return """
                {"turnstile_secret": "0x4AAAAAAA-secreto", "turnstile_site_key": "0x4AAAAAAA-sitio",
                 "allowed_origins": ["%s"], "privacy_policy_url": "https://tienda.example.com/privacidad",
                 "limits": %s}
                """.formatted(SHOP, limits);
    }
}
