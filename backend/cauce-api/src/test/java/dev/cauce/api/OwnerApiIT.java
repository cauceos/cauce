package dev.cauce.api;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static dev.cauce.api.support.FakeLlmProvider.finalReply;
import static dev.cauce.api.support.FakeLlmProvider.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.cauce.business.owner.OwnerNotificationDispatcher;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The owner end to end over a stubbed Telegram (ADR 0007, B7, B8, B9): the owner bot is a second
 * bot of the business; a link code issued over the API and sent to that bot binds the owner's chat
 * and reaches no agent; a case the customer agent hands over is delivered to that chat by the
 * dispatcher, and stays on record as a handoff and a SENT notice.
 */
class OwnerApiIT extends AbstractBusinessApiIT {

    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";
    private static final String OWNER_BOT_SEND = "/bot999:owner-bot/sendMessage";

    private static final WireMockServer TELEGRAM = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        TELEGRAM.start();
    }

    @DynamicPropertySource
    static void telegramBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("cauce.channels.telegram.base-url", TELEGRAM::baseUrl);
    }

    @AfterAll
    static void stopTelegram() {
        TELEGRAM.stop();
    }

    @Autowired
    private OwnerNotificationDispatcher dispatcher;

    private UUID ownerBotId;
    private String ownerBotSecret;

    @BeforeEach
    void ownerBot() throws Exception {
        TELEGRAM.resetAll();
        TELEGRAM.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(OWNER_BOT_SEND)
                .willReturn(okJson("{\"ok\": true}")));
        putJson(clientAuth, "/v1/tenants/" + clientId + "/business-profile", """
                {"name": "Bar La Esquina", "time_zone": "Europe/Madrid", "facts": {},
                 "handoff_categories": ["grupo", "evento", "queja", "otro"]}""").andExpect(status().isOk());
        UUID ownerAgent = createAgent(clientId, clientAuth, List.of());
        JsonNode config = json(mockMvc.perform(post("/v1/agents/" + ownerAgent + "/channels")
                        .header(HttpHeaders.AUTHORIZATION, clientAuth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel_type\": \"telegram\", \"credential\": \"999:owner-bot\"}"))
                .andExpect(status().isCreated()));
        ownerBotId = UUID.fromString(config.get("id").asText());
        ownerBotSecret = config.get("webhook_secret").asText();
    }

    @Test
    void theOwnerLinksTheirChat_andReceivesTheCaseTheAgentHandsOver() throws Exception {
        String code = json(mockMvc.perform(post("/v1/tenants/" + clientId + "/owner-link-codes")
                        .header(HttpHeaders.AUTHORIZATION, partnerAuth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel_config_id\": \"" + ownerBotId + "\"}"))
                .andExpect(status().isCreated())).get("code").asText();
        getAs(clientAuth, "/v1/tenants/" + clientId + "/owner").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("owner_not_linked"));

        // Regression: the bare /start Telegram sends when the chat already exists reaches no agent.
        postToOwnerBot(0, "/start").andExpect(status().isOk());
        TELEGRAM.verify(1, postRequestedFor(urlEqualTo(OWNER_BOT_SEND))
                .withRequestBody(containing("envía aquí el código de vinculación")));
        assertThat(new org.springframework.jdbc.core.JdbcTemplate(adminDataSource)
                .queryForObject("SELECT count(*) FROM pending_invocations", Long.class)).isZero();

        postToOwnerBot(1, "/start " + code).andExpect(status().isOk());

        TELEGRAM.verify(1, postRequestedFor(urlEqualTo(OWNER_BOT_SEND))
                .withRequestBody(containing("Listo: este chat recibirá los avisos de Bar La Esquina.")));
        getAs(clientAuth, "/v1/tenants/" + clientId + "/owner").andExpect(status().isOk())
                .andExpect(jsonPath("$.channel_config_id").value(ownerBotId.toString()));
        assertThat(llm.invocations()).isEmpty(); // the code reached no agent

        UUID agentId = createAgent(clientId, clientAuth, List.of("escalate_to_owner"));
        llm.script(toolCall("escalate_to_owner", Map.of("category", "evento",
                        "summary", "Cumpleaños de 25 personas el 14 de noviembre por la noche",
                        "contact_name", "Marta", "contact_phone", "+34 600 111 222")),
                finalReply("Se lo paso al dueño, que te contactará."));
        sendAndProcess(agentId, "cliente-1", "Quiero celebrar un cumpleaños de 25 personas");

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        TELEGRAM.verify(1, postRequestedFor(urlEqualTo(OWNER_BOT_SEND))
                .withRequestBody(containing("\"chat_id\": \"5550001\"").or(containing("\"chat_id\":\"5550001\"")))
                .withRequestBody(containing("Cumplea")));
        JsonNode handoffs = json(getAs(partnerAuth, "/v1/tenants/" + clientId + "/handoffs?status=OPEN")
                .andExpect(status().isOk()));
        assertThat(handoffs.get("data")).hasSize(1);
        assertThat(handoffs.at("/data/0/contact_name").asText()).isEqualTo("Marta");
        getAs(clientAuth, "/v1/tenants/" + clientId + "/owner-notifications").andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].kind").value("HANDOFF"))
                .andExpect(jsonPath("$.data[0].status").value("SENT"));

        mockMvc.perform(post("/v1/handoffs/" + handoffs.at("/data/0/id").asText() + "/resolve")
                        .header(HttpHeaders.AUTHORIZATION, clientAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        getAs(otherClientAuth, "/v1/tenants/" + clientId + "/handoffs").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void aCaseWithNoOwnerLinked_isKeptOnRecord_andNoticeNotSent() throws Exception {
        UUID agentId = createAgent(clientId, clientAuth, List.of("escalate_to_owner"));
        llm.script(toolCall("escalate_to_owner", Map.of("category", "queja", "summary", "La sopa llegó fría",
                "contact_name", "Luis")), finalReply("Se lo paso al dueño."));

        sendAndProcess(agentId, "cliente-1", "Quiero poner una queja");

        assertThat(dispatcher.dispatchDue()).isZero();
        getAs(clientAuth, "/v1/tenants/" + clientId + "/owner-notifications")
                .andExpect(jsonPath("$.data[0].status").value("NOT_SENT"))
                .andExpect(jsonPath("$.data[0].reason").value("no_owner_recipient"));
        getAs(clientAuth, "/v1/tenants/" + clientId + "/handoffs")
                .andExpect(jsonPath("$.data[0].status").value("OPEN"));
        TELEGRAM.verify(0, postRequestedFor(urlEqualTo(OWNER_BOT_SEND)));
    }

    @Test
    void theNightlySummary_reachesTheLinkedOwner_once_evenWhenRunTwice() throws Exception {
        putJson(clientAuth, "/v1/tenants/" + clientId + "/business-profile", """
                {"name": "Bar La Esquina", "time_zone": "Europe/Madrid", "facts": {},
                 "daily_digest_time": "00:00"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.daily_digest_time").value("00:00:00"));
        String code = json(mockMvc.perform(post("/v1/tenants/" + clientId + "/owner-link-codes")
                        .header(HttpHeaders.AUTHORIZATION, clientAuth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel_config_id\": \"" + ownerBotId + "\"}"))
                .andExpect(status().isCreated())).get("code").asText();
        postToOwnerBot(2, code).andExpect(status().isOk());

        JsonNode first = json(mockMvc.perform(post("/v1/tenants/" + clientId + "/daily-digests")
                .header(HttpHeaders.AUTHORIZATION, clientAuth)).andExpect(status().isOk()));
        JsonNode second = json(mockMvc.perform(post("/v1/tenants/" + clientId + "/daily-digests")
                .header(HttpHeaders.AUTHORIZATION, clientAuth)).andExpect(status().isOk()));
        assertThat(second.get("id")).isEqualTo(first.get("id"));
        assertThat(first.get("content").asText()).contains("Resumen de Bar La Esquina para mañana")
                .endsWith("Nada pendiente.");

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        TELEGRAM.verify(1, postRequestedFor(urlEqualTo(OWNER_BOT_SEND))
                .withRequestBody(containing("Resumen de Bar La Esquina")));
        getAs(partnerAuth, "/v1/tenants/" + clientId + "/daily-digests").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].notified").value(true));
        mockMvc.perform(post("/v1/tenants/" + clientId + "/daily-digests")
                        .header(HttpHeaders.AUTHORIZATION, otherClientAuth))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions postToOwnerBot(long updateId, String text)
            throws Exception {
        return mockMvc.perform(post("/webhooks/channels/" + ownerBotId).contentType(MediaType.APPLICATION_JSON)
                .header(SECRET_HEADER, ownerBotSecret)
                .content("""
                        {"update_id": %d,
                         "message": {"message_id": 7, "from": {"id": 5550001, "first_name": "Dueño"},
                                     "chat": {"id": 5550001, "type": "private"}, "date": 1751623000,
                                     "text": "%s"}}""".formatted(updateId, text)));
    }
}
