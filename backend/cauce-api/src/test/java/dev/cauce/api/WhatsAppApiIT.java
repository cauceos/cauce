package dev.cauce.api;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.cauce.api.agent.CreateAgentRequest;
import dev.cauce.api.channel.CreateChannelConfigRequest;
import dev.cauce.api.channel.RegisterWhatsAppAppRequest;
import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.api.support.FakeLlmProvider;
import dev.cauce.api.tenant.CreateClientRequest;
import dev.cauce.api.tenant.CreatePartnerRequest;
import dev.cauce.channels.whatsapp.WhatsAppProperties;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.worker.PendingInvocationWorker;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end tests of the WhatsApp channel (ADR 0006) against a real datasource: the REST surface
 * with API-key auth and hierarchical RLS, and the webhook — Meta's subscription check, a signed
 * text message in, the agent's reply out to the BSUID through a WireMock stand-in for the Graph
 * API, and the text-only answer to a message of another type. The worker runs with a one-hour poll
 * (mirror of {@code TelegramRoundTripIT}); the only processing is the manual {@code pollAndProcess()}.
 */
@TestPropertySource(properties = {
        "cauce.orchestration.worker.enabled=true",
        "cauce.orchestration.worker.poll-interval-ms=3600000",
        "cauce.orchestration.worker.reaper.enabled=false",
        // The fake provider registers as "anthropic": the real one must not, even when the
        // environment running the build has an ANTHROPIC_API_KEY.
        "cauce.llm.anthropic.enabled=false"
})
@Import(MessagingApiIT.FakeLlmConfig.class)
class WhatsAppApiIT extends AbstractApiIntegrationTest {

    static final String APP_SECRET = "meta-app-secret-for-tests";
    private static final String BSUID = "ES.13491208655302741918";
    private static final String SEND_PATH = "/v24.0/1111111111/messages";

    /** Class-managed (not @WireMockTest) so its URL can feed @DynamicPropertySource. */
    private static final WireMockServer GRAPH = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        GRAPH.start();
    }

    @DynamicPropertySource
    static void graphBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("cauce.channels.whatsapp.base-url", GRAPH::baseUrl);
    }

    @AfterAll
    static void stopGraphStub() {
        GRAPH.stop();
    }

    @Autowired
    private PendingInvocationWorker worker;

    @Autowired
    private FakeLlmProvider fakeLlmProvider;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ObjectMapper objectMapper;

    private String operatorAuth;
    private UUID operatorId;
    private UUID partnerId;
    private String partnerAuth;
    private UUID clientId;
    private String clientAuth;

    @BeforeEach
    void setUp() throws Exception {
        truncateAll();
        TenantContext.clear();
        operatorId = tenantService.bootstrapOperator("Operator").id();
        operatorAuth = bearerFor(operatorId);
        partnerId = idFrom(mockMvc.perform(postAs(operatorAuth, "/v1/tenants/partner",
                        new CreatePartnerRequest("Partner", operatorId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        partnerAuth = bearerFor(partnerId);
        clientId = idFrom(mockMvc.perform(postAs(partnerAuth, "/v1/tenants/client",
                        new CreateClientRequest("Client", partnerId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        clientAuth = bearerFor(clientId);
        GRAPH.resetAll();
        GRAPH.stubFor(WireMock.post(SEND_PATH).willReturn(okJson("{\"messages\":[{\"id\":\"wamid.OUT\"}]}")));
        fakeLlmProvider.reset();
    }

    // --- the webhook, end to end ---

    @Test
    void subscriptionCheck_echoesTheChallenge_onlyForTheAppsVerifyToken() throws Exception {
        JsonNode app = registerAppJson();
        String appId = app.get("id").asText();
        String verifyToken = app.get("verify_token").asText();

        mockMvc.perform(get("/webhooks/whatsapp/" + appId).param("hub.mode", "subscribe")
                        .param("hub.verify_token", verifyToken).param("hub.challenge", "1158201444"))
                .andExpect(status().isOk())
                .andExpect(content().string("1158201444"));
        mockMvc.perform(get("/webhooks/whatsapp/" + appId).param("hub.mode", "subscribe")
                        .param("hub.verify_token", "wrong").param("hub.challenge", "1158201444"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSignedTextMessage_opensAThreadByBsuid_andTheReplyGoesBackToTheBsuid() throws Exception {
        String appId = registerApp(partnerAuth, partnerId);
        UUID agentId = createAgent();
        UUID numberId = bindNumber(appId, agentId);

        postSigned(appId, textMessage("wamid.IN1", "Hola, quiero una cita")).andExpect(status().isOk());
        postSigned(appId, textMessage("wamid.IN1", "Hola, quiero una cita")).andExpect(status().isOk()); // redelivery

        UUID conversationId = jdbc().queryForObject("SELECT c.id FROM conversations c JOIN identities i "
                + "ON i.id = c.identity_id WHERE i.value = ? AND i.kind = 'PROVIDER_USER_ID'", UUID.class, BSUID);
        assertThat(jdbc().queryForObject("SELECT channel_config_id FROM conversations WHERE id = ?", UUID.class,
                conversationId)).isEqualTo(numberId);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM messages WHERE conversation_id = ? AND role = 'USER'",
                Integer.class, conversationId)).isEqualTo(1);

        worker.pollAndProcess();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> GRAPH.verify(1, postRequestedFor(urlEqualTo(SEND_PATH))
                .withHeader("Authorization", equalTo("Bearer EAAG-access-token"))
                .withRequestBody(matchingJsonPath("$.recipient", equalTo(BSUID)))
                .withRequestBody(matchingJsonPath("$.text.body", equalTo(FakeLlmProvider.REPLY)))));
        // No phone number is stored anywhere (W1).
        assertThat(jdbc().queryForObject("SELECT count(*) FROM identities WHERE value LIKE '%600111222%'",
                Integer.class)).isZero();
    }

    @Test
    void aBadSignature_is401_andNothingIsIngested() throws Exception {
        String appId = registerApp(partnerAuth, partnerId);
        bindNumber(appId, createAgent());
        byte[] body = textMessage("wamid.IN2", "Hola").getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/webhooks/whatsapp/" + appId).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", sign(body, "not-the-app-secret")).content(body))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc().queryForObject("SELECT count(*) FROM conversations", Integer.class)).isZero();
    }

    @Test
    void anImage_isAcknowledged_audited_andAnsweredWithTheTextOnlyReply() throws Exception {
        String appId = registerApp(partnerAuth, partnerId);
        bindNumber(appId, createAgent());

        postSigned(appId, envelope("""
                {"from_user_id":"%s","id":"wamid.IMG","type":"image","image":{"id":"media-1"}}""".formatted(BSUID)))
                .andExpect(status().isOk());

        assertThat(jdbc().queryForObject("SELECT count(*) FROM conversations", Integer.class)).isZero();
        assertThat(jdbc().queryForObject("SELECT count(*) FROM audit_outbox WHERE event_type = "
                + "'conduct.message.unsupported'", Integer.class)).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> GRAPH.verify(1, postRequestedFor(urlEqualTo(SEND_PATH))
                .withRequestBody(matchingJsonPath("$.recipient", equalTo(BSUID)))
                .withRequestBody(matchingJsonPath("$.text.body",
                        equalTo(new WhatsAppProperties().getUnsupportedMessageReply())))));
    }

    @Test
    void registerApp_returnsTheWebhookPathAndTheVerifyTokenOnce_neverTheSecret() throws Exception {
        String body = mockMvc.perform(postAs(partnerAuth, "/v1/tenants/" + partnerId + "/whatsapp-apps",
                        new RegisterWhatsAppAppRequest("1234567890", APP_SECRET)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant_id").value(partnerId.toString()))
                .andExpect(jsonPath("$.meta_app_id").value("1234567890"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.verify_token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(body);
        String appId = created.get("id").asText();
        assertThat(created.get("webhook_path").asText()).isEqualTo("/webhooks/whatsapp/" + appId);
        assertThat(body).doesNotContain(APP_SECRET);

        String read = mockMvc.perform(getAs(partnerAuth, "/v1/whatsapp-apps/" + appId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verify_token").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(read).doesNotContain(APP_SECRET);
        // A client below the partner does not see its partner's app.
        mockMvc.perform(getAs(clientAuth, "/v1/whatsapp-apps/" + appId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("whatsapp_app_not_found"));
    }

    @Test
    void registerApp_forATenantOutsideTheCallersScope_is404() throws Exception {
        mockMvc.perform(postAs(clientAuth, "/v1/tenants/" + partnerId + "/whatsapp-apps",
                        new RegisterWhatsAppAppRequest("1234567890", APP_SECRET)))
                .andExpect(status().isNotFound());
    }

    @Test
    void bindNumber_byThePartner_forItsClientsAgent_returnsTheBindingWithoutASecret() throws Exception {
        String appId = registerApp(partnerAuth, partnerId);
        UUID agentId = createAgent();

        String body = mockMvc.perform(postAs(partnerAuth, "/v1/agents/" + agentId + "/channels",
                        new CreateChannelConfigRequest("whatsapp", "EAAG-access-token", UUID.fromString(appId),
                                "1111111111")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel_type").value("whatsapp"))
                .andExpect(jsonPath("$.whatsapp_app_id").value(appId))
                .andExpect(jsonPath("$.phone_number_id").value("1111111111"))
                .andExpect(jsonPath("$.webhook_secret").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("EAAG-access-token");

        // One number is one door.
        mockMvc.perform(postAs(partnerAuth, "/v1/agents/" + agentId + "/channels",
                        new CreateChannelConfigRequest("whatsapp", "t", UUID.fromString(appId), "1111111111")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("phone_number_already_bound"));
        // A WhatsApp binding names its app and number.
        mockMvc.perform(postAs(partnerAuth, "/v1/agents/" + agentId + "/channels",
                        new CreateChannelConfigRequest("whatsapp", "t", null, "2222222222")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_channel_config_request"));
    }

    @Test
    void aTextRedeliveredConcurrently_isIngestedOnce_andEveryDeliveryGets2xx() throws Exception {
        String appId = registerApp(partnerAuth, partnerId);
        bindNumber(appId, createAgent());
        String body = textMessage("wamid.RACE", "Hola");

        List<Integer> statuses = race(6, () -> postSigned(appId, body).andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(200);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM messages WHERE role = 'USER'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM pending_invocations", Integer.class)).isEqualTo(1);
    }

    @Test
    void aNonTextMessageRedelivered_sequentiallyOrConcurrently_isAuditedAndAnsweredOnce() throws Exception {
        String appId = registerApp(partnerAuth, partnerId);
        bindNumber(appId, createAgent());
        String body = envelope("""
                {"from_user_id":"%s","id":"wamid.AUDIO","type":"audio","audio":{"id":"media-9"}}""".formatted(BSUID));

        postSigned(appId, body).andExpect(status().isOk());
        List<Integer> statuses = race(6, () -> postSigned(appId, body).andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(200);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM audit_outbox WHERE event_type = "
                + "'conduct.message.unsupported'", Integer.class)).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM channel_inbound_receipts", Integer.class))
                .isEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                GRAPH.verify(1, postRequestedFor(urlEqualTo(SEND_PATH))));
        Thread.sleep(500); // a late duplicate send would land here
        GRAPH.verify(1, postRequestedFor(urlEqualTo(SEND_PATH)));
    }

    // --- helpers ---

    /**
     * Fires {@code request} {@code parallel} times at once (a provider redelivering while the first
     * delivery is still in flight) and returns the HTTP statuses.
     */
    private List<Integer> race(int parallel, Callable<Integer> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        CyclicBarrier barrier = new CyclicBarrier(parallel);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < parallel; i++) {
            tasks.add(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return request.call();
            });
        }
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> future : pool.invokeAll(tasks)) {
                statuses.add(future.get(20, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        return statuses;
    }

    private JsonNode registerAppJson() throws Exception {
        return objectMapper.readTree(mockMvc.perform(postAs(partnerAuth, "/v1/tenants/" + partnerId + "/whatsapp-apps",
                        new RegisterWhatsAppAppRequest("1234567890", APP_SECRET)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID bindNumber(String appId, UUID agentId) throws Exception {
        return idFrom(mockMvc.perform(postAs(partnerAuth, "/v1/agents/" + agentId + "/channels",
                        new CreateChannelConfigRequest("whatsapp", "EAAG-access-token", UUID.fromString(appId),
                                "1111111111")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions postSigned(String appId, String body) throws Exception {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        return mockMvc.perform(post("/webhooks/whatsapp/" + appId).contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature-256", sign(raw, APP_SECRET)).content(raw));
    }

    private static String textMessage(String wamid, String text) {
        return envelope("""
                {"from":"34600111222","from_user_id":"%s","id":"%s","timestamp":"1761048000",
                 "type":"text","text":{"body":"%s"}}""".formatted(BSUID, wamid, text));
    }

    private static String envelope(String message) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"waba-1","changes":[{"field":"messages",
                 "value":{"messaging_product":"whatsapp","metadata":{"display_phone_number":"34911111111",
                 "phone_number_id":"1111111111"},"messages":[%s]}}]}]}""".formatted(message);
    }

    /** Meta's X-Hub-Signature-256, computed independently of the production class. */
    private static String sign(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(adminDataSource);
    }

    UUID createAgent() throws Exception {
        return idFrom(mockMvc.perform(postAs(clientAuth, "/v1/tenants/" + clientId + "/agents",
                        new CreateAgentRequest("Bot", "You are helpful", "anthropic", "claude-sonnet-4-7",
                                null, null, null, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    String registerApp(String auth, UUID tenantId) throws Exception {
        return idFrom(mockMvc.perform(postAs(auth, "/v1/tenants/" + tenantId + "/whatsapp-apps",
                        new RegisterWhatsAppAppRequest("1234567890", APP_SECRET)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).toString();
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

    private MockHttpServletRequestBuilder postAs(String auth, String path, Object body) throws Exception {
        return post(path)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder getAs(String auth, String path) {
        return get(path).header(HttpHeaders.AUTHORIZATION, auth);
    }
}
