package dev.cauce.api.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.security.ApiKeyAuthenticationFilter;
import dev.cauce.api.web.RequestIdFilter;
import dev.cauce.api.web.TenantContextFilter;
import dev.cauce.core.agent.Agent;
import dev.cauce.core.tenant.InvalidTenantTierException;
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.tenancy.AgentService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@link AgentController}: HTTP contract, validation, and the mapping of
 * domain exceptions to status codes, with the service mocked.
 */
@WebMvcTest(controllers = AgentController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {RequestIdFilter.class, TenantContextFilter.class, ApiKeyAuthenticationFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AgentService agentService;

    private String validBody() throws Exception {
        return objectMapper.writeValueAsString(
                new CreateAgentRequest("Bot", "You are helpful", "anthropic", "claude-opus-4-8", null, null));
    }

    @Test
    void createAgent_valid_returns201WithAgentsLocation() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Agent agent = Agent.create(tenantId, "Bot", "You are helpful", "anthropic", "claude-opus-4-8");
        given(agentService.createAgent(eq(tenantId), eq("Bot"), eq("You are helpful"),
                eq("anthropic"), eq("claude-opus-4-8"), any(), any())).willReturn(agent);

        mockMvc.perform(post("/v1/tenants/" + tenantId + "/agents")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/v1/agents/" + agent.id()))
                .andExpect(jsonPath("$.id").value(agent.id().toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.model_provider").value("anthropic"));
    }

    @Test
    void createAgent_blankSystemPrompt_returns400() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String body = objectMapper.writeValueAsString(
                new CreateAgentRequest("Bot", "  ", "anthropic", "claude-opus-4-8", null, null));

        mockMvc.perform(post("/v1/tenants/" + tenantId + "/agents")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("systemPrompt"));
    }

    @Test
    void createAgent_nonClientTenant_returns422() throws Exception {
        UUID tenantId = UUID.randomUUID();
        given(agentService.createAgent(eq(tenantId), any(), any(), any(), any(), any(), any()))
                .willThrow(new InvalidTenantTierException("not a CLIENT"));

        mockMvc.perform(post("/v1/tenants/" + tenantId + "/agents")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_tenant_tier"));
    }

    @Test
    void createAgent_unknownTenant_returns404() throws Exception {
        UUID tenantId = UUID.randomUUID();
        given(agentService.createAgent(eq(tenantId), any(), any(), any(), any(), any(), any()))
                .willThrow(new TenantNotFoundException("no tenant"));

        mockMvc.perform(post("/v1/tenants/" + tenantId + "/agents")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("tenant_not_found"));
    }

    @Test
    void getAgent_existing_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        Agent agent = Agent.create(UUID.randomUUID(), "Bot", "You are helpful", "anthropic", "claude-opus-4-8");
        given(agentService.getAgent(id)).willReturn(Optional.of(agent));

        mockMvc.perform(get("/v1/agents/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(agent.id().toString()))
                .andExpect(jsonPath("$.max_response_tokens").value(Agent.DEFAULT_MAX_RESPONSE_TOKENS));
    }

    @Test
    void getAgent_missing_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        given(agentService.getAgent(id)).willReturn(Optional.empty());

        mockMvc.perform(get("/v1/agents/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("agent_not_found"));
    }

    @Test
    void listAgents_returns200List() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Agent agent = Agent.create(tenantId, "Bot", "You are helpful", "anthropic", "claude-opus-4-8");
        given(agentService.listAgentsForTenant(tenantId)).willReturn(List.of(agent));

        mockMvc.perform(get("/v1/tenants/" + tenantId + "/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("DRAFT"));
    }
}
