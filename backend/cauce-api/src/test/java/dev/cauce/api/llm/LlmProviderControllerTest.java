package dev.cauce.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.security.ApiKeyAuthenticationFilter;
import dev.cauce.api.web.RequestIdFilter;
import dev.cauce.llm.spi.LlmProviderRegistry;
import dev.cauce.tenancy.AgentService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-slice tests for {@link LlmProviderController}, with the registry and the agent service mocked. */
@WebMvcTest(controllers = LlmProviderController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {RequestIdFilter.class, ApiKeyAuthenticationFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
class LlmProviderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LlmProviderRegistry registry;

    @MockitoBean
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        given(agentService.supportedProviders()).willReturn(Set.of("openai", "anthropic", "mistral", "ollama"));
    }

    @Test
    void list_everyAcceptedProvider_withWhetherItIsConfigured_sortedAsOnePage() throws Exception {
        given(registry.availableProviders()).willReturn(Set.of("ollama"));

        mockMvc.perform(get("/v1/llm-providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].id").value("anthropic"))
                .andExpect(jsonPath("$.data[0].configured").value(false))
                .andExpect(jsonPath("$.data[2].id").value("ollama"))
                .andExpect(jsonPath("$.data[2].configured").value(true))
                .andExpect(jsonPath("$.next_cursor").isEmpty());
    }

    @Test
    void list_eachProvider_carriesItsNameAndTheYesNoOnly() throws Exception {
        given(registry.availableProviders()).willReturn(Set.of("anthropic", "ollama"));

        String body = mockMvc.perform(get("/v1/llm-providers"))
                .andReturn().getResponse().getContentAsString();

        // Never a base URL, a key, or any other setting: the two fields, nothing more.
        for (JsonNode provider : objectMapper.readTree(body).get("data")) {
            List<String> fields = new ArrayList<>();
            provider.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactly("id", "configured");
        }
    }

    @Test
    void list_aConfiguredIdTheServiceDoesNotList_isStillReported() throws Exception {
        given(registry.availableProviders()).willReturn(Set.of("vllm"));

        mockMvc.perform(get("/v1/llm-providers"))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[?(@.id == 'vllm')].configured").value(true));
    }
}
