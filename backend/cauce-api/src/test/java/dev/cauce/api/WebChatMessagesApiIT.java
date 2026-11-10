package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cauce.channels.webchat.TurnstileVerifier;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A web chat's messages of ADR 0014, W3, end to end: a session sends into the ingest every channel
 * uses, as its own SESSION_ID identity on web_chat; it polls its current thread and sees only its
 * words and the replies it got; the bounds and the session hold; and a person may start over.
 */
class WebChatMessagesApiIT extends AbstractBusinessApiIT {

    private static final String SHOP = "https://tienda.example.com";

    @MockitoBean
    private TurnstileVerifier turnstile;

    private JdbcTemplate admin;
    private String key;
    private String token;

    @BeforeEach
    void channelAndSession() throws Exception {
        admin = new JdbcTemplate(adminDataSource);
        when(turnstile.verify(eq("0x4AAAAAAA-secreto"), eq("token-bueno"))).thenReturn(true);
        // A spend limit needs the model priced, or the invocation fails as misconfigured (ADR 0010).
        postJson(bearerFor(operatorId), "/v1/llm-prices", """
                {"provider": "anthropic", "model": "claude-sonnet-4-7", "input_usd_per_mtok": 2.00,
                 "output_usd_per_mtok": 10.00}
                """).andExpect(status().isCreated());
        putJson(partnerAuth, "/v1/tenants/" + clientId + "/spend-limit", """
                {"monthly_limit_usd": 50}
                """).andExpect(status().isOk());
        key = createChannel(createAgent(clientId, clientAuth, List.of("get_current_time")));
        token = openSession(key);
    }

    @Test
    void aMessage_isIngestedAsTheSessionsIdentity_andThePollShowsOnlyWordsAndReplies_thenNoReplyPending() throws Exception {
        llm.useToolOnFirstCall();
        JsonNode sent = json(send(token, "{\"content\": \"  ¿Abrís hoy?  \"}").andExpect(status().isAccepted()));
        String conversationId = sent.get("conversation_id").asText();

        assertThat(admin.queryForObject("SELECT i.kind || ':' || i.channel_type || ':' || (i.value = s.id::text) "
                + "FROM conversations c JOIN identities i ON i.id = c.identity_id, web_sessions s "
                + "WHERE c.id = ?::uuid", String.class, conversationId)).isEqualTo("SESSION_ID:web_chat:true");
        read(token, "").andExpect(jsonPath("$.awaiting_reply").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].role").value("user"))
                .andExpect(jsonPath("$.data[0].text").value("¿Abrís hoy?"));

        worker.pollAndProcess();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> read(token, "")
                .andExpect(jsonPath("$.awaiting_reply").value(false))
                .andExpect(jsonPath("$.data.length()").value(2)));
        JsonNode page = json(read(token, ""));
        assertThat(page.get("conversation_id").asText()).isEqualTo(conversationId);
        assertThat(page.get("data").get(1).get("role").asText()).isEqualTo("agent");
        assertThat(page.toString()).doesNotContain("get_current_time");
        // After the cursor, only what is new.
        String first = page.get("data").get(0).get("id").asText();
        read(token, "?cursor=" + first).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].role").value("agent"));
        read(token, "?cursor=" + UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_message"));
    }

    @Test
    void withoutAValidSessionOfThisChannel_nothingIsSentOrRead() throws Exception {
        String otherKey = createChannel(createAgent(clientId, clientAuth, List.of()));
        String otherToken = openSession(otherKey);

        send(null, "{\"content\": \"hola\"}").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_session"));
        send("no-es-un-token", "{\"content\": \"hola\"}").andExpect(status().isUnauthorized());
        send(otherToken, "{\"content\": \"hola\"}").andExpect(status().isUnauthorized());
        read(otherToken, "").andExpect(status().isUnauthorized());
        assertThat(admin.queryForObject("SELECT count(*) FROM messages", Integer.class)).isZero();
    }

    @Test
    void theBounds_aBlankOrTooLongMessage_andAFullThread_areRefused_andANewThreadGoesOn() throws Exception {
        send(token, "{\"content\": \"   \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_message"));
        send(token, "{\"content\": \"" + "a".repeat(2001) + "\"}").andExpect(status().isBadRequest());

        String conversationId = json(send(token, "{\"content\": \"hola\"}")).get("conversation_id").asText();
        admin.update("UPDATE pending_invocations SET status = 'COMPLETED', completed_at = now()");
        admin.update("INSERT INTO messages (id, conversation_id, role, parts) SELECT gen_random_uuid(), ?::uuid, "
                + "'AGENT', jsonb_build_array(jsonb_build_object('type', 'text', 'text', 'x')) "
                + "FROM generate_series(1, 199)", conversationId);

        send(token, "{\"content\": \"otra\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("thread_full"));
        String fresh = json(send(token, "{\"content\": \"empiezo de nuevo\", \"new_thread\": true}")
                .andExpect(status().isAccepted())).get("conversation_id").asText();

        assertThat(fresh).isNotEqualTo(conversationId);
        assertThat(admin.queryForObject("SELECT status FROM conversations WHERE id = ?::uuid", String.class,
                conversationId)).isEqualTo("CLOSED");
        read(token, "").andExpect(jsonPath("$.conversation_id").value(fresh))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void theSameIdempotencyKey_replaysTheMessage_butNeverAcrossSessions() throws Exception {
        String otherToken = openSession(key);
        JsonNode first = json(sendWithKey(token, "k-1"));
        JsonNode again = json(sendWithKey(token, "k-1"));
        JsonNode otherSession = json(sendWithKey(otherToken, "k-1"));

        assertThat(again.get("message_id")).isEqualTo(first.get("message_id"));
        assertThat(otherSession.get("message_id")).isNotEqualTo(first.get("message_id"));
    }

    @Test
    void aDisabledChannel_takesNoNewMessages_butItsPersonMayStillReadTheThread() throws Exception {
        send(token, "{\"content\": \"hola\"}").andExpect(status().isAccepted());
        String configId = admin.queryForObject("SELECT id::text FROM channel_configs WHERE public_key = ?",
                String.class, key);
        postJson(clientAuth, "/v1/channel-configs/" + configId + "/disable", "{}").andExpect(status().isOk());

        send(token, "{\"content\": \"¿sigue ahí?\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("channel_disabled"));
        read(token, "").andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void eachAuthenticatedRequest_slidesTheSession() throws Exception {
        admin.update("UPDATE web_sessions SET expires_at = now() + interval '1 hour'");

        read(token, "").andExpect(status().isOk());

        assertThat(admin.queryForObject("SELECT expires_at > now() + interval '29 days' FROM web_sessions "
                + "ORDER BY created_at LIMIT 1", Boolean.class)).isTrue();
    }

    private ResultActions send(String sessionToken, String json) throws Exception {
        var request = post("/web/v1/" + key + "/messages").header(HttpHeaders.ORIGIN, SHOP)
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (sessionToken != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken);
        }
        return mockMvc.perform(request);
    }

    private ResultActions sendWithKey(String sessionToken, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/web/v1/" + key + "/messages").header(HttpHeaders.ORIGIN, SHOP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken).header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\": \"hola\"}"))
                .andExpect(status().isAccepted());
    }

    private ResultActions read(String sessionToken, String query) throws Exception {
        return mockMvc.perform(get("/web/v1/" + key + "/messages" + query).header(HttpHeaders.ORIGIN, SHOP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken));
    }

    private String createChannel(UUID agentId) throws Exception {
        return json(postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", """
                {"turnstile_secret": "0x4AAAAAAA-secreto", "turnstile_site_key": "0x4AAAAAAA-sitio",
                 "allowed_origins": ["%s"], "privacy_policy_url": "https://tienda.example.com/privacidad"}
                """.formatted(SHOP)).andExpect(status().isCreated())).get("public_key").asText();
    }

    private String openSession(String publicKey) throws Exception {
        return json(mockMvc.perform(post("/web/v1/" + publicKey + "/sessions").header(HttpHeaders.ORIGIN, SHOP)
                .contentType(MediaType.APPLICATION_JSON).content("{\"turnstile_token\": \"token-bueno\"}"))
                .andExpect(status().isCreated())).get("session_token").asText();
    }
}
