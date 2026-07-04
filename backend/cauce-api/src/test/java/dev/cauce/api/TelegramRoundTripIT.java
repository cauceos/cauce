package dev.cauce.api;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.cauce.api.agent.CreateAgentRequest;
import dev.cauce.api.channel.CreateChannelConfigRequest;
import dev.cauce.api.message.PostMessageRequest;
import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.api.support.FakeLlmProvider;
import dev.cauce.api.tenant.CreateClientRequest;
import dev.cauce.api.tenant.CreatePartnerRequest;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.worker.PendingInvocationWorker;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The full Telegram round-trip: webhook Update in → ingest → agentic loop (FakeLlmProvider)
 * → committed AGENT reply → explicit dispatch port → outbound {@code sendMessage} against a
 * WireMock stand-in for the Bot API. Also proves the negatives: the {@code api} channel
 * never triggers outbound, and a provider outage is best-effort (the invocation completes
 * and the reply stays readable by polling).
 *
 * <p>The worker is enabled with a one-hour poll (mirror of {@code MessagingApiIT}); the only
 * processing is the manual {@code pollAndProcess()}. Delivery runs on the channel outbound
 * executor, so assertions await the WireMock request.
 */
@TestPropertySource(properties = {
        "cauce.orchestration.worker.enabled=true",
        "cauce.orchestration.worker.poll-interval-ms=3600000",
        "cauce.orchestration.worker.reaper.enabled=false",
        "cauce.llm.anthropic.enabled=false"
})
@Import(MessagingApiIT.FakeLlmConfig.class)
class TelegramRoundTripIT extends AbstractApiIntegrationTest {

    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    /** Class-managed (not @WireMockTest) so its URL can feed @DynamicPropertySource. */
    private static final WireMockServer TELEGRAM =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        TELEGRAM.start();
    }

    @DynamicPropertySource
    static void telegramBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("cauce.channels.telegram.base-url", TELEGRAM::baseUrl);
    }

    @AfterAll
    static void stopTelegramStub() {
        TELEGRAM.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PendingInvocationWorker worker;

    private JdbcTemplate jdbc;

    private String clientAuth;
    private UUID agentId;
    private UUID configId;
    private String webhookSecret;

    @BeforeEach
    void setUp() throws Exception {
        truncateAll();
        TenantContext.clear();
        TELEGRAM.resetAll();
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
                .andReturn().getResponse().getContentAsString();
        configId = idFrom(configBody);
        webhookSecret = objectMapper.readTree(configBody).get("webhook_secret").asText();
    }

    @Test
    void telegramMessage_processed_deliversTheReplyBackToTheOriginChat() throws Exception {
        TELEGRAM.stubFor(post("/bot12345:bot-token/sendMessage")
                .willReturn(okJson("{\"ok\": true}")));

        postUpdate(update(1001, 987654321L, "Hola")).andExpect(status().isOk());
        worker.pollAndProcess();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                TELEGRAM.verify(1, postRequestedFor(urlEqualTo("/bot12345:bot-token/sendMessage"))
                        .withRequestBody(equalToJson("{\"chat_id\": \"987654321\", \"text\": \""
                                + FakeLlmProvider.REPLY + "\"}"))));
        // The reply is also persisted: the poll fallback keeps working alongside delivery.
        UUID conversationId = telegramConversationId();
        assertThat(agentMessageCount(conversationId)).isEqualTo(1);
    }

    @Test
    void apiChannelMessage_processed_triggersNoOutboundDelivery() throws Exception {
        mockMvc.perform(postAs(clientAuth, "/v1/agents/" + agentId + "/messages",
                        new PostMessageRequest("user-1", "Hola por API")))
                .andExpect(status().isAccepted());
        worker.pollAndProcess();

        UUID conversationId = jdbc.queryForObject(
                "SELECT id FROM conversations WHERE agent_id = ? AND channel_type = 'api'",
                UUID.class, agentId);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(agentMessageCount(conversationId)).isEqualTo(1));

        // Processing finished and nothing was (or will be) sent outbound for the api channel:
        // the no-adapter branch is decided synchronously before anything is queued.
        TELEGRAM.verify(0, postRequestedFor(urlMatching("/bot.*")));
    }

    @Test
    void telegramOutage_duringDelivery_doesNotDegradeTheInvocation() throws Exception {
        TELEGRAM.stubFor(post("/bot12345:bot-token/sendMessage").willReturn(serverError()));

        postUpdate(update(2002, 555000111L, "Hola")).andExpect(status().isOk());
        worker.pollAndProcess();

        UUID conversationId = telegramConversationId();
        // The invocation completes and the reply is persisted despite the failed delivery...
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(agentMessageCount(conversationId)).isEqualTo(1);
            String status = jdbc.queryForObject(
                    "SELECT status FROM pending_invocations WHERE conversation_id = ?",
                    String.class, conversationId);
            assertThat(status).isEqualTo("COMPLETED");
        });
        // ...the delivery WAS attempted (and swallowed) — awaiting it also drains the async
        // task so it cannot leak into the next test's WireMock journal...
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                TELEGRAM.verify(1, postRequestedFor(urlEqualTo("/bot12345:bot-token/sendMessage"))));
        // ...and the reply stays readable through the poll API (best-effort, explicit).
        mockMvc.perform(get("/v1/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, clientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].role").value("AGENT"))
                .andExpect(jsonPath("$[1].content").value(FakeLlmProvider.REPLY));
    }

    // --- helpers ---

    private org.springframework.test.web.servlet.ResultActions postUpdate(String updateJson)
            throws Exception {
        return mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/webhooks/channels/" + configId)
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

    private UUID telegramConversationId() {
        return jdbc.queryForObject(
                "SELECT id FROM conversations WHERE agent_id = ? AND channel_type = 'telegram'",
                UUID.class, agentId);
    }

    private int agentMessageCount(UUID conversationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ? AND role = 'AGENT'",
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
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }
}
