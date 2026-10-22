package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.worker.PendingInvocationWorker;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Measures what prompt caching saves on the reference bar, against the real model: one customer
 * conversation of six turns through the whole stack, then the usage the ledger recorded per call,
 * cached and not, and its cost with and without the cache.
 *
 * <p>Manual, and never part of the normal build: it spends real tokens (about 0.10 US$), so it runs
 * only when {@code CAUCE_LLM_MEASURE=1} and an {@code ANTHROPIC_API_KEY} are both in the environment:
 * <pre>
 *   CAUCE_LLM_MEASURE=1 ./gradlew :cauce-api:test --tests '*PromptCachingMeasurementIT' --rerun-tasks --max-workers=2
 * </pre>
 * The prices are the ones published for {@link #MODEL} when this was written (2026-10-22): input
 * 2.00, cache write (5 minutes) 2.50, cache read 0.20 and output 10.00 US$ per million tokens. They
 * only turn the measured tokens into an estimate printed here; nothing stores a cost.
 */
@EnabledIfEnvironmentVariable(named = "CAUCE_LLM_MEASURE", matches = "1")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@TestPropertySource(properties = {
        "cauce.orchestration.worker.enabled=true",
        "cauce.orchestration.worker.poll-interval-ms=3600000",
        "cauce.orchestration.worker.reaper.enabled=false"
})
class PromptCachingMeasurementIT extends AbstractApiIntegrationTest {

    private static final String MODEL = "claude-sonnet-5-5";
    private static final double INPUT = 2.00;
    private static final double CACHE_WRITE = 2.50;
    private static final double CACHE_READ = 0.20;
    private static final double OUTPUT = 10.00;
    private static final Path PACK = Path.of("..", "..", "examples", "hosteleria");
    private static final List<String> TURNS = List.of(
            "¿Tenéis wifi?",
            "¿Qué tapas tenéis?",
            "Mesa para 4 el próximo sábado a las 21:30, soy Ana, 600 123 456",
            "Sí",
            "Al final seremos 6, ¿puedes cambiar la reserva?",
            "Sí");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private PendingInvocationWorker worker;

    @Test
    void aConversationWithTheBar_readsMostOfEachPromptFromTheCache() throws Exception {
        truncateAll();
        UUID operatorId = tenantService.bootstrapOperator("Operator").id();
        UUID partnerId = asTenant(operatorId, () -> tenantService.createPartner("Partner", operatorId).id());
        UUID barId = asTenant(partnerId, () -> tenantService.createClient("Bar La Esquina", partnerId).id());
        String auth = "Bearer " + asTenant(barId, () -> apiKeyService.createApiKey(barId, "measure").plaintextKey());
        putJson(auth, "/v1/tenants/" + barId + "/business-profile", read("bar-ejemplo/ficha.json"));
        putJson(auth, "/v1/tenants/" + barId + "/catalog", read("bar-ejemplo/carta.json"));
        putJson(auth, "/v1/tenants/" + barId + "/agenda", read("bar-ejemplo/agenda.json"));
        List<String> tools = new ArrayList<>();
        objectMapper.readTree(read("herramientas.json")).get("agente_clientes").forEach(t -> tools.add(t.asText()));
        String agent = mockMvc.perform(post("/v1/tenants/" + barId + "/agents").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Camarero de La Esquina",
                                "system_prompt", read("agente-clientes.prompt.md"), "model_provider", "anthropic",
                                "model_name", MODEL, "tools", tools))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String agentId = objectMapper.readTree(agent).get("id").asText();

        for (String turn : TURNS) {
            String accepted = mockMvc.perform(post("/v1/agents/" + agentId + "/messages")
                            .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("external_identity_ref", "ana",
                                    "content", turn))))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            String invocationId = objectMapper.readTree(accepted).get("invocation_id").asText();
            worker.pollAndProcess();
            await().atMost(Duration.ofMinutes(2)).untilAsserted(() -> mockMvc.perform(
                            get("/v1/invocations/" + invocationId).header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(jsonPath("$.status").value("COMPLETED")));
        }

        List<Map<String, Object>> calls = new JdbcTemplate(adminDataSource).queryForList(
                "SELECT round_index, input_tokens, cache_read_tokens, cache_write_tokens, output_tokens "
                        + "FROM llm_usage_records ORDER BY created_at, id");
        long input = 0;
        long read = 0;
        long write = 0;
        long output = 0;
        System.out.println("call round   input   cached_read   cached_write   uncached   output");
        for (int i = 0; i < calls.size(); i++) {
            Map<String, Object> call = calls.get(i);
            int in = (int) call.get("input_tokens");
            int r = (int) call.get("cache_read_tokens");
            int w = (int) call.get("cache_write_tokens");
            int out = (int) call.get("output_tokens");
            System.out.printf("%4d %5d %7d %13d %14d %10d %8d%n", i + 1, (int) call.get("round_index"), in, r, w,
                    in - r - w, out);
            input += in;
            read += r;
            write += w;
            output += out;
        }
        double without = (input * INPUT + output * OUTPUT) / 1_000_000;
        double with = ((input - read - write) * INPUT + read * CACHE_READ + write * CACHE_WRITE + output * OUTPUT)
                / 1_000_000;
        System.out.printf("%d calls, %d turns: %d input tokens, %d read from the cache (%.0f %%), %d written%n",
                calls.size(), TURNS.size(), input, read, 100.0 * read / input, write);
        System.out.printf("cost without caching %.4f US$, with caching %.4f US$: %.0f %% saved%n",
                without, with, 100 * (1 - with / without));

        // The standing check: a conversation that goes on reads its prefix back from the cache.
        assertThat(read).isPositive();
    }

    private void putJson(String auth, String path, String json) throws Exception {
        mockMvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isOk());
    }

    private static String read(String file) throws Exception {
        return Files.readString(PACK.resolve(file), StandardCharsets.UTF_8);
    }

    private static <T> T asTenant(UUID tenantId, Supplier<T> action) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }
}
