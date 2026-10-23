package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cauce.llm.exception.LlmInvalidRequestException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * The logs of a message's path carry its ids and never its content (ADR 0011, L1, L2): a provider
 * that echoes the person's words in its error, as providers do, leaves them in the invocation's
 * record, not in the log. At INFO, as in production: the detail is logged at DEBUG, for development.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "logging.level.dev.cauce=INFO") // as in production; DEBUG keeps the detail
class LogHygieneIT extends AbstractBusinessApiIT {

    private static final String WORDS = "Mesa para 4 a nombre de Zacarías Ortuño, 699 111 222";

    @Test
    void aFailedInvocation_logsItsIds_andNeitherTheMessageNorTheProvidersEchoOfIt(CapturedOutput output)
            throws Exception {
        UUID agentId = createAgent(clientId, clientAuth, List.of());
        llm.failNextWith(new LlmInvalidRequestException("anthropic", "claude-sonnet-4-7",
                "HTTP 400: messages.0.content: " + WORDS));

        JsonNode accepted = json(mockMvc.perform(post("/v1/agents/" + agentId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, clientAuth).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("external_identity_ref", "persona-1",
                                "content", WORDS))))
                .andExpect(status().isAccepted()));
        String invocationId = accepted.get("invocation_id").asText();
        worker.pollAndProcess();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                getAs(clientAuth, "/v1/invocations/" + invocationId).andExpect(jsonPath("$.status").value("FAILED")));

        assertThat(output.getAll())
                .contains("LlmInvalidRequestException[provider=anthropic, model=claude-sonnet-4-7]")
                .contains(invocationId)
                .doesNotContain("Zacarías").doesNotContain("699 111 222");
    }
}
