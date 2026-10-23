package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The hospitality sector pack under {@code examples/hosteleria} stays loadable: the reference
 * bar's sheet, menu and agenda are accepted by the API as they are, and every tool the pack grants
 * is registered. A pack that drifts from the API fails here, not in front of a bar owner.
 */
class ReferenceBarPackIT extends AbstractBusinessApiIT {

    /** Gradle runs the tests from the module directory, {@code backend/cauce-api}. */
    private static final Path PACK = Path.of("..", "..", "examples", "hosteleria");

    @Test
    void theReferenceBar_loadsAsIs_andTheAgentsCanBeCreatedWithTheirGrant() throws Exception {
        putJson(clientAuth, "/v1/tenants/" + clientId + "/business-profile", read("bar-ejemplo/ficha.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handoff_categories.length()").value(4));
        putJson(clientAuth, "/v1/tenants/" + clientId + "/catalog", read("bar-ejemplo/carta.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declared_attributes[0]").value("alergenos"));
        putJson(clientAuth, "/v1/tenants/" + clientId + "/agenda", read("bar-ejemplo/agenda.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shifts.length()").value(2));

        JsonNode grants = objectMapper.readTree(read("herramientas.json"));
        JsonNode fallbacks = objectMapper.readTree(read("respaldo.json"));
        for (String agent : List.of("agente_clientes", "agente_dueno")) {
            List<String> tools = new ArrayList<>();
            grants.get(agent).forEach(tool -> tools.add(tool.asText()));
            String prompt = read(agent.equals("agente_clientes") ? "agente-clientes.prompt.md" : "agente-dueno.prompt.md");
            mockMvc.perform(post("/v1/tenants/" + clientId + "/agents").header(HttpHeaders.AUTHORIZATION, clientAuth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", agent, "system_prompt", prompt,
                                    "model_provider", "anthropic", "model_name", "claude-sonnet-4-7", "tools", tools,
                                    "fallback_reply", fallbacks.get(agent).asText()))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.fallback_reply").value(fallbacks.get(agent).asText()));
        }
        assertThat(grants.get("agente_clientes")).hasSize(11);
        JsonNode retention = objectMapper.readTree(read("retencion-cache.json"));
        assertThat(retention.get("agente_clientes").asText()).isEqualTo("LONG");
        assertThat(dev.cauce.core.agent.PromptCacheRetention.valueOf(retention.get("agente_dueno").asText()))
                .isNotNull();
    }

    private static String read(String file) throws Exception {
        return Files.readString(PACK.resolve(file), StandardCharsets.UTF_8);
    }
}
