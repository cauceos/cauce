package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.agent.CreateAgentRequest;
import dev.cauce.api.channel.CreateChannelConfigRequest;
import dev.cauce.api.message.PostMessageRequest;
import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.api.tenant.CreateClientRequest;
import dev.cauce.api.tenant.CreatePartnerRequest;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.events.InvocationRequested;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end test of the inbound Telegram channel over the real HTTP surface: bind a
 * channel config via REST (capturing the once-shown webhook secret), then drive the
 * unauthenticated webhook endpoint with Bot API {@code Update} payloads. Verifies the
 * security order (nothing processed without the secret), the normalization into the
 * existing ingest boundary, and — the reason idempotency was built first — that a Telegram
 * redelivery (same {@code update_id}) replays instead of duplicating.
 *
 * <p>{@link RecordApplicationEvents} captures {@code InvocationRequested} because MockMvc
 * dispatches on the test thread. No worker runs (module test default): assertions are on
 * the queued state, and the untouched {@code "api"} path is asserted in the same context.
 */
@RecordApplicationEvents
class TelegramWebhookIT extends AbstractApiIntegrationTest {

    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationEvents applicationEvents;

    private JdbcTemplate jdbc;

    private String clientAuth;
    private UUID agentId;
    private UUID configId;
    private String webhookSecret;

    @BeforeEach
    void setUp() throws Exception {
        truncateAll();
        TenantContext.clear();
        jdbc = new JdbcTemplate(adminDataSource);

        UUID operatorId = tenantService.bootstrapOperator("Operator").id();
        String operatorAuth = bearerFor(operatorId);
        UUID partnerId = idFrom(mockMvc.perform(postAs(operatorAuth, "/v1/tenants/partner",
                        new CreatePartnerRequest("Partner", operatorId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID clientId = idFrom(mockMvc.perform(postAs(bearerFor(partnerId), "/v1/tenants/client",
                        new CreateClientRequest("Client", partnerId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        clientAuth = bearerFor(clientId);
        agentId = idFrom(mockMvc.perform(postAs(clientAuth, "/v1/tenants/" + clientId + "/agents",
                        new CreateAgentRequest("Bot", "You are helpful", "anthropic",
                                "claude-sonnet-4-7", null, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        String configBody = mockMvc.perform(postAs(clientAuth, "/v1/agents/" + agentId + "/channels",
                        new CreateChannelConfigRequest("telegram", "12345:bot-token")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel_type").value("telegram"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.webhook_secret").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        configId = idFrom(configBody);
        webhookSecret = objectMapper.readTree(configBody).get("webhook_secret").asText();
    }

    @Test
    void webhook_textUpdate_createsTelegramConversationUserMessageAndInvocation() throws Exception {
        postUpdate(update(1001, 987654321L, "Hola, quiero una cita"))
                .andExpect(status().isOk());

        UUID conversationId = jdbc.queryForObject(
                "SELECT id FROM conversations WHERE agent_id = ? AND channel_type = 'telegram' "
                        + "AND external_identity_ref = '987654321'", UUID.class, agentId);
        assertThat(conversationId).isNotNull();
        assertThat(userMessageCount(conversationId)).isEqualTo(1);
        Integer invocations = jdbc.queryForObject(
                "SELECT count(*) FROM pending_invocations WHERE conversation_id = ?",
                Integer.class, conversationId);
        assertThat(invocations).isEqualTo(1);
    }

    @Test
    void webhook_redeliveredUpdate_isDeduplicatedEndToEnd() throws Exception {
        String update = update(2002, 987654321L, "Hola");
        postUpdate(update).andExpect(status().isOk());
        postUpdate(update).andExpect(status().isOk()); // Telegram redelivery: same update_id

        UUID conversationId = jdbc.queryForObject(
                "SELECT id FROM conversations WHERE agent_id = ? AND channel_type = 'telegram'",
                UUID.class, agentId);
        assertThat(userMessageCount(conversationId)).isEqualTo(1);
        Integer invocations = jdbc.queryForObject(
                "SELECT count(*) FROM pending_invocations WHERE conversation_id = ?",
                Integer.class, conversationId);
        assertThat(invocations).isEqualTo(1);
        assertThat(applicationEvents.stream(InvocationRequested.class).count()).isEqualTo(1);
    }

    @Test
    void webhook_wrongSecret_is401AndPersistsNothing() throws Exception {
        mockMvc.perform(post("/webhooks/channels/" + configId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SECRET_HEADER, "wrong-secret")
                        .content(update(3003, 987654321L, "Hola")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("webhook_authentication_failed"));

        Integer conversations = jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE channel_type = 'telegram'", Integer.class);
        assertThat(conversations).isZero();
    }

    @Test
    void webhook_unknownConfig_is404() throws Exception {
        mockMvc.perform(post("/webhooks/channels/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(SECRET_HEADER, webhookSecret)
                        .content(update(4004, 987654321L, "Hola")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("channel_config_not_found"));
    }

    @Test
    void webhook_unsupportedUpdate_is200WithoutEffects() throws Exception {
        String editedMessage = """
                {"update_id": 5005,
                 "edited_message": {"chat": {"id": 987654321}, "text": "edited"}}
                """;
        postUpdate(editedMessage).andExpect(status().isOk());

        Integer conversations = jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE channel_type = 'telegram'", Integer.class);
        assertThat(conversations).isZero();
    }

    @Test
    void apiChannel_nextToTelegram_staysIntact() throws Exception {
        // The pre-channels public path: POST /v1 messages still ingests on channel "api" and
        // the thread is still readable by polling GET messages.
        String body = mockMvc.perform(postAs(clientAuth, "/v1/agents/" + agentId + "/messages",
                        new PostMessageRequest("user-1", "Hola por API")))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID conversationId = UUID.fromString(
                objectMapper.readTree(body).get("conversation_id").asText());

        String channel = jdbc.queryForObject(
                "SELECT channel_type FROM conversations WHERE id = ?", String.class, conversationId);
        assertThat(channel).isEqualTo("api");
        mockMvc.perform(get("/v1/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, clientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].content").value("Hola por API"));
    }

    // --- helpers ---

    private org.springframework.test.web.servlet.ResultActions postUpdate(String updateJson)
            throws Exception {
        return mockMvc.perform(post("/webhooks/channels/" + configId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(SECRET_HEADER, webhookSecret)
                .content(updateJson));
    }

    private static String update(long updateId, long chatId, String text) {
        return """
                {"update_id": %d,
                 "message": {"message_id": 42,
                             "from": {"id": 111, "first_name": "Ana"},
                             "chat": {"id": %d, "type": "private"},
                             "date": 1751623000,
                             "text": "%s"}}
                """.formatted(updateId, chatId, text);
    }

    private int userMessageCount(UUID conversationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ? AND role = 'USER'",
                Integer.class, conversationId);
    }

    private UUID idFrom(String body) throws Exception {
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private String bearerFor(UUID tenantId) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            return "Bearer " + apiKeyService.createApiKey(tenantId, "it-key").plaintextKey();
        } finally {
            TenantContext.clear();
        }
    }

    private MockHttpServletRequestBuilder postAs(String auth, String path, Object body)
            throws Exception {
        return post(path)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }
}
