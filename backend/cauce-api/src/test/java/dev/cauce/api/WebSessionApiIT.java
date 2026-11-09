package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cauce.channels.webchat.ResolvedWebChat;
import dev.cauce.channels.webchat.TurnstileUnavailableException;
import dev.cauce.channels.webchat.TurnstileVerifier;
import dev.cauce.channels.webchat.WebChatChannelService;
import dev.cauce.channels.webchat.WebSession;
import dev.cauce.channels.webchat.WebSessionService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Anonymous web-chat sessions of ADR 0014, W2, end to end: opened only with a Turnstile token that
 * verifies (and the acceptance a channel may ask for), stored as a hash, 30 days sliding by the
 * database clock, bound to their channel, and purged once expired.
 */
class WebSessionApiIT extends AbstractBusinessApiIT {

    private static final String SHOP = "https://tienda.example.com";

    @MockitoBean
    private TurnstileVerifier turnstile;

    @Autowired
    private WebSessionService sessions;

    @Autowired
    private WebChatChannelService channels;

    private JdbcTemplate admin;

    @BeforeEach
    void limit() throws Exception {
        admin = new JdbcTemplate(adminDataSource);
        putJson(partnerAuth, "/v1/tenants/" + clientId + "/spend-limit", """
                {"monthly_limit_usd": 50}
                """).andExpect(status().isOk());
        when(turnstile.verify(eq("0x4AAAAAAA-secreto"), eq("token-bueno"))).thenReturn(true);
    }

    @Test
    void aVerifiedTurnstileToken_opensASession_storedAsAHash_for30Days_withoutAnIdentityYet() throws Exception {
        String key = createChannel("NOTICE");

        String body = open(key, """
                {"turnstile_token": "token-bueno"}
                """).andExpect(status().isCreated())
                .andExpect(jsonPath("$.session_token").isString())
                .andExpect(jsonPath("$.expires_at").isString())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("session_token").asText();
        Instant expires = Instant.parse(objectMapper.readTree(body).get("expires_at").asText());

        assertThat(token).hasSize(43);
        assertThat(Duration.between(Instant.now(), expires)).isBetween(Duration.ofDays(29), Duration.ofDays(31));
        assertThat(admin.queryForObject("SELECT count(*) FROM web_sessions WHERE token_hash = ?", Integer.class, token))
                .isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM web_sessions", Integer.class)).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT consented_at IS NULL FROM web_sessions", Boolean.class)).isTrue();
        assertThat(admin.queryForObject("SELECT count(*) FROM identities WHERE kind = 'SESSION_ID'", Integer.class))
                .isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM audit_outbox WHERE event_type = "
                + "'conduct.web.session.opened'", Integer.class)).isEqualTo(1);
    }

    @Test
    void aTokenThatDoesNotVerify_isRefused_andTurnstileDown_isUnavailable_withNothingStored() throws Exception {
        String key = createChannel("NOTICE");
        when(turnstile.verify(anyString(), eq("token-malo"))).thenReturn(false);
        when(turnstile.verify(anyString(), eq("token-caido"))).thenThrow(new TurnstileUnavailableException("down", null));

        open(key, """
                {"turnstile_token": "token-malo"}
                """).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("turnstile_failed"));
        open(key, """
                {"turnstile_token": "token-caido"}
                """).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("turnstile_unavailable"));
        assertThat(admin.queryForObject("SELECT count(*) FROM web_sessions", Integer.class)).isZero();
    }

    @Test
    void aChannelThatAsksForAcceptance_opensASessionOnlyWithIt_andRecordsWhen() throws Exception {
        String key = createChannel("ACCEPTANCE");

        open(key, """
                {"turnstile_token": "token-bueno"}
                """).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("consent_required"));
        open(key, """
                {"turnstile_token": "token-bueno", "consent": true}
                """).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("consent_required"));
        open(key, """
                {"turnstile_token": "token-bueno", "consent": true, "notice_version": "es-1"}
                """).andExpect(status().isCreated());

        // What was accepted: the widget's notice text, by its version, and the privacy link.
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("notice:es-1\nprivacy:https://tienda.example.com/privacidad"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(admin.queryForObject("SELECT consent_notice_hash FROM web_sessions WHERE consented_at IS NOT NULL",
                String.class)).isEqualTo(expected);
    }

    @Test
    void aDisallowedOrigin_neverReachesTurnstile() throws Exception {
        String key = createChannel("NOTICE");

        mockMvc.perform(post("/web/v1/" + key + "/sessions").header(HttpHeaders.ORIGIN, "https://otra.example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"turnstile_token\": \"token-bueno\"}"))
                .andExpect(status().isForbidden());
        verify(turnstile, never()).verify(any(), any());
    }

    @Test
    void aToken_authenticatesOnlyOnItsChannel_slidesItsExpiry_andOnceExpiredIsPurged() throws Exception {
        String key = createChannel("NOTICE");
        String otherKey = createChannel("NOTICE");
        String token = objectMapper.readTree(open(key, """
                {"turnstile_token": "token-bueno"}
                """).andReturn().getResponse().getContentAsString()).get("session_token").asText();
        ResolvedWebChat channel = channels.resolve(key).orElseThrow();

        Optional<WebSession> session = sessions.authenticate(channel, token);
        assertThat(session).isPresent();
        assertThat(sessions.authenticate(channels.resolve(otherKey).orElseThrow(), token)).isEmpty();
        assertThat(sessions.authenticate(channel, "otro-token")).isEmpty();

        admin.update("UPDATE web_sessions SET expires_at = now() + interval '1 day'");
        assertThat(asTenant(clientId, () -> sessions.slide(session.get().id()))).isTrue();
        assertThat(admin.queryForObject("SELECT expires_at > now() + interval '29 days' FROM web_sessions",
                Boolean.class)).isTrue();

        admin.update("UPDATE web_sessions SET expires_at = now() - interval '1 minute'");
        assertThat(sessions.authenticate(channel, token)).isEmpty();
        assertThat(asTenant(clientId, () -> sessions.slide(session.get().id()))).isFalse();
        assertThat(sessions.purgeExpired()).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT count(*) FROM web_sessions", Integer.class)).isZero();
    }

    @Test
    void theEmergencySwitch_stopsNewSessionsWithAClearError_freesTheSpendLimit_andNeedsItBackToEnable() throws Exception {
        String key = createChannel("NOTICE");
        String configId = channels.resolve(key).orElseThrow().channelConfigId().toString();

        postJson(clientAuth, "/v1/channel-configs/" + configId + "/disable", "{}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/web/v1/" + key + "/config").header(HttpHeaders.ORIGIN, SHOP))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        open(key, """
                {"turnstile_token": "token-bueno"}
                """).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("channel_disabled"));
        verify(turnstile, never()).verify(any(), any());
        assertThat(admin.queryForObject("SELECT count(*) FROM audit_outbox WHERE event_type = 'admin.channel.disabled'",
                Integer.class)).isEqualTo(1);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/v1/tenants/" + clientId + "/spend-limit").header(HttpHeaders.AUTHORIZATION, partnerAuth))
                .andExpect(status().is2xxSuccessful());
        postJson(clientAuth, "/v1/channel-configs/" + configId + "/enable", "{}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("spend_limit_required"));

        putJson(partnerAuth, "/v1/tenants/" + clientId + "/spend-limit", """
                {"monthly_limit_usd": 50}
                """).andExpect(status().isOk());
        postJson(clientAuth, "/v1/channel-configs/" + configId + "/enable", "{}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        open(key, """
                {"turnstile_token": "token-bueno"}
                """).andExpect(status().isCreated());
        postJson(otherClientAuth, "/v1/channel-configs/" + configId + "/disable", "{}").andExpect(status().isNotFound());
    }

    private ResultActions open(String key, String json) throws Exception {
        return mockMvc.perform(post("/web/v1/" + key + "/sessions").header(HttpHeaders.ORIGIN, SHOP)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String createChannel(String consentMode) throws Exception {
        UUID agentId = createAgent(clientId, clientAuth, List.of());
        return json(postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", """
                {"turnstile_secret": "0x4AAAAAAA-secreto", "turnstile_site_key": "0x4AAAAAAA-sitio",
                 "allowed_origins": ["%s"], "privacy_policy_url": "https://tienda.example.com/privacidad",
                 "consent_mode": "%s"}
                """.formatted(SHOP, consentMode)).andExpect(status().isCreated())).get("public_key").asText();
    }
}
