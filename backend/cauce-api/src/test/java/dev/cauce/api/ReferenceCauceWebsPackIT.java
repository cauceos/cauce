package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cauce.channels.webchat.WebChatAssistant;
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
 * The first shop's pack under {@code examples/caucewebs} stays loadable (ADR 0014, amendment of
 * 2026-11-10): CauceWebs's sheet and its catalog (the web plans, the shop and the videos: all it sells) are accepted by the API as they are, every
 * tool the pack grants is registered, and the agents can be created with their texts. A pack that drifts
 * from the API fails here, not in front of a customer.
 */
class ReferenceCauceWebsPackIT extends AbstractBusinessApiIT {

    /** Gradle runs the tests from the module directory, {@code backend/cauce-api}. */
    private static final Path PACK = Path.of("..", "..", "examples", "caucewebs");

    @Test
    void cauceWebs_loadsAsIs_withItsPlansAndTheDemoCategory_andTheAgentsCanBeCreated() throws Exception {
        putJson(clientAuth, "/v1/tenants/" + clientId + "/business-profile", read("caucewebs/ficha.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handoff_categories[0]").value("demo"));
        putJson(clientAuth, "/v1/tenants/" + clientId + "/catalog", read("caucewebs/catalogo.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declared_attributes.length()").value(0))
                .andExpect(jsonPath("$.items.length()").value(5));

        JsonNode grants = objectMapper.readTree(read("herramientas.json"));
        JsonNode fallbacks = objectMapper.readTree(read("respaldo.json"));
        JsonNode unsupported = objectMapper.readTree(read("no-soportado.json"));
        for (String agent : List.of("agente_clientes", "agente_dueno")) {
            List<String> tools = new ArrayList<>();
            grants.get(agent).forEach(tool -> tools.add(tool.asText()));
            String prompt = read(agent.equals("agente_clientes") ? "agente-clientes.prompt.md" : "agente-dueno.prompt.md");
            mockMvc.perform(post("/v1/tenants/" + clientId + "/agents").header(HttpHeaders.AUTHORIZATION, clientAuth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", agent, "system_prompt", prompt,
                                    "model_provider", "anthropic", "model_name", "claude-sonnet-4-7", "tools", tools,
                                    "fallback_reply", fallbacks.get(agent).asText(),
                                    "unsupported_message_reply", unsupported.get(agent).asText()))))
                    .andExpect(status().isCreated());
        }
        assertThat(grants.get("agente_clientes")).hasSize(7);
        // The shop-assistant mode (ADR 0014, WC8) as PUT /v1/web-chat-channels/{id}/assistant takes it.
        WebChatAssistant assistant = objectMapper.readValue(read("asistente.json"), WebChatAssistant.class);
        assertThat(assistant.enabled()).isTrue();
        assertThat(assistant.templates()).containsKeys("comparing", "back_to_product", "idle_cart", "exit_intent");
        assertThat(assistant.routes()).extracting(WebChatAssistant.Route::path).containsExactly("/", "/pagar");
        // The launch on caucewebs.es (ADR 0014, amendment of 2026-11-11): phase 1 is the bubble alone, one
        // variant named for the phase, no A/B. Opening on its own goes without consent only because the
        // widget then stores nothing but the opened and closed marks; no counter, no purchase attribution.
        assertThat(assistant.proactiveOpening()).isFalse();
        assertThat(assistant.greetingFor("idle_cart", null)).isEmpty();
        assertThat(assistant.variants()).extracting(WebChatAssistant.Variant::id).containsExactly("burbuja");
        assertThat(assistant.proactiveRequiresConsent()).isFalse();
        assertThat(assistant.openingCounter()).isFalse();
        assertThat(assistant.purchaseAttribution()).isFalse();
        assertThat(objectMapper.readTree(read("retencion-cache.json")).get("agente_clientes").asText()).isEqualTo("LONG");
    }

    private static String read(String file) throws Exception {
        return Files.readString(PACK.resolve(file), StandardCharsets.UTF_8);
    }
}
