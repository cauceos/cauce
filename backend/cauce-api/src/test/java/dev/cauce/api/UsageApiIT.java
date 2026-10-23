package dev.cauce.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The usage surface end to end (ADR 0010): the price table an operator fills and the hierarchy
 * reads.
 */
class UsageApiIT extends AbstractBusinessApiIT {

    private static final String SONNET_PRICE = """
            {"provider": "anthropic", "model": "claude-sonnet-4-7", "input_usd_per_mtok": 2.00,
             "output_usd_per_mtok": 10.00, "cache_read_usd_per_mtok": 0.20, "cache_write_usd_per_mtok": 2.50}
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

    protected ResultActions postJson(String auth, String path, String json) throws Exception {
        return mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
