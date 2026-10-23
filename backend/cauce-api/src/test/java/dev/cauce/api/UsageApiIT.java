package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The usage surface end to end (ADR 0010): the price table an operator fills and the hierarchy
 * reads, and the usage a tenant reads of itself and of its descendants.
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

    protected ResultActions postJson(String auth, String path, String json) throws Exception {
        return mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
