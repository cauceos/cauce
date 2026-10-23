package dev.cauce.api.llm;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cauce.api.security.ApiKeyAuthenticationFilter;
import dev.cauce.api.web.RequestIdFilter;
import dev.cauce.llm.spi.LlmProviderRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web-slice tests for {@link LlmProviderController}, with the registry mocked. */
@WebMvcTest(controllers = LlmProviderController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {RequestIdFilter.class, ApiKeyAuthenticationFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
class LlmProviderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmProviderRegistry registry;

    @Test
    void list_registeredProviders_returnsTheirIdsSortedAsOnePage() throws Exception {
        given(registry.availableProviders()).willReturn(Set.of("ollama", "anthropic"));

        mockMvc.perform(get("/v1/llm-providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("anthropic"))
                .andExpect(jsonPath("$.data[1].id").value("ollama"))
                .andExpect(jsonPath("$.next_cursor").isEmpty());
    }

    @Test
    void list_noProviderConfigured_returnsAnEmptyPage() throws Exception {
        given(registry.availableProviders()).willReturn(Set.of());

        mockMvc.perform(get("/v1/llm-providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
