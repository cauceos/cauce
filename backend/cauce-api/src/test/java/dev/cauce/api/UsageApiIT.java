package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The usage surface end to end (ADR 0010): the price table an operator fills and the hierarchy
 * reads, the usage a tenant reads of itself and of its descendants, and the monthly spend limit an
 * ancestor sets on a client: warned at 80 %, the fallback reply at 100 %, the owner told once each.
 */
class UsageApiIT extends AbstractBusinessApiIT {

    private static final String SONNET_PRICE = """
            {"provider": "anthropic", "model": "claude-sonnet-4-7", "input_usd_per_mtok": 2.00,
             "output_usd_per_mtok": 10.00, "cache_read_usd_per_mtok": 0.20, "cache_write_usd_per_mtok": 2.50}
            """;

    /** The fake model's reply is 1 token in and 1 out: this price makes one reply cost 0.60 US$. */
    private static final String ROUND_PRICE = """
            {"provider": "anthropic", "model": "claude-sonnet-4-7", "input_usd_per_mtok": 100000,
             "output_usd_per_mtok": 500000}
            """;

    @Test
    void theOperatorAddsAPrice_andTheHierarchyReadsIt() throws Exception {
        postJson(bearerFor(operatorId), "/v1/llm-prices", SONNET_PRICE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenant_id").value(operatorId.toString()))
                .andExpect(jsonPath("$.model").value("claude-sonnet-4-7"))
                .andExpect(jsonPath("$.input_usd_per_mtok").value(2.0))
                .andExpect(jsonPath("$.cache_write_usd_per_mtok").value(2.5))
                .andExpect(jsonPath("$.valid_from").isNotEmpty());

        getAs(clientAuth, "/v1/llm-prices")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].output_usd_per_mtok").value(10.0))
                .andExpect(jsonPath("$.next_cursor").isEmpty());
    }

    @Test
    void aPartner_cannotAddAPrice() throws Exception {
        postJson(partnerAuth, "/v1/llm-prices", SONNET_PRICE)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_tenant_tier"));
    }

    @Test
    void aNegativeOrMissingPrice_isAValidationError() throws Exception {
        postJson(bearerFor(operatorId), "/v1/llm-prices", """
                {"provider": "anthropic", "model": "m", "input_usd_per_mtok": -1}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @Test
    void aPartner_readsItsClientsUsage_inTokensCallsConversationsAndCost_andTheReadIsAudited() throws Exception {
        postJson(bearerFor(operatorId), "/v1/llm-prices", ROUND_PRICE).andExpect(status().isCreated());
        UUID agentId = createAgent(clientId, clientAuth, List.of());
        sendAndProcess(agentId, "persona-1", "Hola");

        getAs(partnerAuth, "/v1/tenants/" + clientId + "/usage?granularity=month&group_by=agent")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant_id").value(clientId.toString()))
                .andExpect(jsonPath("$.granularity").value("month"))
                .andExpect(jsonPath("$.group_by").value("agent"))
                .andExpect(jsonPath("$.time_zone").value("UTC"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].tenant_id").value(clientId.toString()))
                .andExpect(jsonPath("$.data[0].agent_id").value(agentId.toString()))
                .andExpect(jsonPath("$.data[0].input_tokens").value(1))
                .andExpect(jsonPath("$.data[0].output_tokens").value(1))
                .andExpect(jsonPath("$.data[0].calls").value(1))
                .andExpect(jsonPath("$.data[0].conversations").value(1))
                .andExpect(jsonPath("$.data[0].cost_usd").value(0.6))
                .andExpect(jsonPath("$.data[0].unpriced_calls").value(0));
        getAs(clientAuth, "/v1/tenants/" + clientId + "/usage")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].agent_id").isEmpty());

        // The partner's read is in the client's chain; the client's own read is not.
        List<String> actors = new JdbcTemplate(adminDataSource).queryForList("SELECT payload->>'actor_tenant_id' "
                + "FROM audit_outbox WHERE tenant_id = ? AND event_type = 'admin.usage.read'", String.class, clientId);
        assertThat(actors).containsExactly(partnerId.toString());
    }

    @Test
    void aSibling_cannotReadTheUsage_andABadParameterIsABadRequest() throws Exception {
        getAs(otherClientAuth, "/v1/tenants/" + clientId + "/usage")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("tenant_not_found"));
        getAs(clientAuth, "/v1/tenants/" + clientId + "/usage?time_zone=%2B02:00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
        getAs(clientAuth, "/v1/tenants/" + clientId + "/usage?granularity=week")
                .andExpect(status().isBadRequest());
        getAs(clientAuth, "/v1/tenants/" + clientId + "/usage?from=2026-10-02&to=2026-10-01")
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSpendLimit_isSetByAnAncestor_readByTheClient_andNeverSetByTheClient() throws Exception {
        putJson(partnerAuth, "/v1/tenants/" + clientId + "/spend-limit", """
                {"monthly_limit_usd": 50}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthly_limit_usd").value(50.0))
                .andExpect(jsonPath("$.spent_usd").value(0))
                .andExpect(jsonPath("$.state").value("ok"))
                .andExpect(jsonPath("$.period").isNotEmpty());
        getAs(clientAuth, "/v1/tenants/" + clientId + "/spend-limit")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthly_limit_usd").value(50.0));

        putJson(clientAuth, "/v1/tenants/" + clientId + "/spend-limit", """
                {"monthly_limit_usd": 5000}
                """)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("spend_limit_set_by_ancestor"));
        putJson(bearerFor(operatorId), "/v1/tenants/" + partnerId + "/spend-limit", """
                {"monthly_limit_usd": 50}
                """)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_tenant_tier"));
        putJson(partnerAuth, "/v1/tenants/" + clientId + "/spend-limit", """
                {"monthly_limit_usd": 0}
                """)
                .andExpect(status().isBadRequest());
        getAs(otherClientAuth, "/v1/tenants/" + clientId + "/spend-limit").andExpect(status().isNotFound());

        mockMvc.perform(delete("/v1/tenants/" + clientId + "/spend-limit").header(HttpHeaders.AUTHORIZATION, partnerAuth))
                .andExpect(status().isNoContent());
        getAs(clientAuth, "/v1/tenants/" + clientId + "/spend-limit")
                .andExpect(jsonPath("$.monthly_limit_usd").isEmpty())
                .andExpect(jsonPath("$.state").value("none"));
    }

    @Test
    void atTheLimit_theCustomerGetsTheFallback_andTheOwnerIsToldOnce() throws Exception {
        postJson(bearerFor(operatorId), "/v1/llm-prices", ROUND_PRICE).andExpect(status().isCreated());
        UUID agentId = createAgent(clientId, clientAuth, List.of());
        // Each reply costs 0.60: the third message is checked at 1.20 (86 %), the fourth at 1.80.
        putJson(partnerAuth, "/v1/tenants/" + clientId + "/spend-limit", """
                {"monthly_limit_usd": 1.40}
                """).andExpect(status().isOk());

        sendAndProcess(agentId, "persona-1", "Hola");
        sendAndProcess(agentId, "persona-1", "¿Abrís hoy?");
        sendAndProcess(agentId, "persona-1", "¿Y mañana?");
        JsonNode blocked = sendAndAwait(agentId, "persona-1", "¿Tenéis terraza?", "FAILED");
        sendAndAwait(agentId, "persona-2", "Hola", "FAILED");

        getAs(clientAuth, "/v1/invocations/" + blocked.get("invocation_id").asText())
                .andExpect(jsonPath("$.failure_reason").value("SPEND_LIMIT_REACHED"));
        JsonNode messages = json(getAs(clientAuth, "/v1/conversations/" + blocked.get("conversation_id").asText()
                + "/messages?limit=200"));
        JsonNode last = messages.get("data").get(messages.get("data").size() - 1);
        assertThat(last.get("role").asText()).isEqualTo("FALLBACK");
        assertThat(llm.invocations()).hasSize(3);

        // Told once per threshold, even with no owner linked (recorded, not sent).
        List<Map<String, Object>> notices = new JdbcTemplate(adminDataSource).queryForList(
                "SELECT kind, status, reason FROM owner_notifications WHERE tenant_id = ? ORDER BY created_at, id",
                clientId);
        assertThat(notices).extracting(n -> n.get("kind")).containsExactly("SPEND_WARNING", "SPEND_LIMIT_REACHED");
        assertThat(notices).extracting(n -> n.get("status")).containsOnly("NOT_SENT");
        getAs(partnerAuth, "/v1/tenants/" + clientId + "/spend-limit")
                .andExpect(jsonPath("$.state").value("reached"))
                .andExpect(jsonPath("$.spent_usd").value(1.8));
    }

    @Test
    void underALimit_aModelWithoutPrice_failsAsAMisconfiguration() throws Exception {
        UUID agentId = createAgent(clientId, clientAuth, List.of());
        putJson(partnerAuth, "/v1/tenants/" + clientId + "/spend-limit", """
                {"monthly_limit_usd": 10}
                """).andExpect(status().isOk());

        JsonNode accepted = sendAndAwait(agentId, "persona-1", "Hola", "FAILED");

        getAs(clientAuth, "/v1/invocations/" + accepted.get("invocation_id").asText())
                .andExpect(jsonPath("$.failure_reason").value("AGENT_MISCONFIGURED"));
        assertThat(llm.invocations()).isEmpty();
    }

    /** Posts {@code content} as {@code person}, runs the worker and waits for {@code status}; the 202 body. */
    private JsonNode sendAndAwait(UUID agentId, String person, String content, String expected) throws Exception {
        JsonNode accepted = json(mockMvc.perform(post("/v1/agents/" + agentId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, clientAuth).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("external_identity_ref", person,
                                "content", content))))
                .andExpect(status().isAccepted()));
        worker.pollAndProcess();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                getAs(clientAuth, "/v1/invocations/" + accepted.get("invocation_id").asText())
                        .andExpect(jsonPath("$.status").value(expected)));
        return accepted;
    }

    protected ResultActions postJson(String auth, String path, String json) throws Exception {
        return mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
