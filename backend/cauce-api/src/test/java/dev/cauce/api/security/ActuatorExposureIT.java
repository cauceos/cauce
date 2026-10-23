package dev.cauce.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What the unauthenticated actuator tells anyone who asks (ADR 0011, A3): the external monitor
 * pings {@code /actuator/health}, which answers its status and nothing else — no components, no
 * database or Redis detail, no version. Metrics need an API key. Any profile but {@code dev}, which
 * shows the details on a developer's machine.
 */
class ActuatorExposureIT extends AbstractApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void health_unauthenticated_answersTheStatusOnly() throws Exception {
        String body = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode health = objectMapper.readTree(body);
        assertThat(health.properties()).extracting(java.util.Map.Entry::getKey).containsExactly("status");
        assertThat(health.get("status").asText()).isEqualTo("UP");
    }

    @Test
    void info_unauthenticated_disclosesNothing() throws Exception {
        String body = mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).isEmpty()).as(body).isTrue();
    }

    @Test
    void metrics_unauthenticated_isRefused() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }
}
