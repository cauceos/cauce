package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.cauce.orchestration.tracing.MessageTracing;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * A message is one trace, end to end, and its spans say nothing of what was said (ADR 0011, T2,
 * T3): the HTTP request that brings it in, the invocation the worker runs later on another thread,
 * every model call and every tool call share one trace id, and no attribute holds the message's
 * text.
 */
@AutoConfigureObservability(metrics = false)
@Import(TracingIT.InMemorySpans.class)
class TracingIT extends AbstractBusinessApiIT {

    private static final String WORDS = "Mesa para 4 a nombre de Zacarías Ortuño, 699 111 222";

    @TestConfiguration
    static class InMemorySpans {

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @Autowired
    private InMemorySpanExporter spans;

    @Autowired
    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void clearSpans() {
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        spans.reset();
    }

    @Test
    void aMessage_isOneTrace_fromItsRequestToEveryModelAndToolCall() throws Exception {
        UUID agentId = createAgent(clientId, clientAuth, List.of("get_current_time"));
        llm.useToolOnFirstCall();

        sendAndProcess(agentId, "persona-1", WORDS);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
            assertThat(named("cauce.invocation")).hasSize(1);
        });
        SpanData invocation = named("cauce.invocation").get(0);
        String traceId = invocation.getTraceId();
        SpanData request = spans.getFinishedSpanItems().stream()
                .filter(span -> span.getKind() == SpanKind.SERVER && span.getTraceId().equals(traceId))
                .findFirst().orElseThrow(() -> new AssertionError("the invocation does not continue the request's trace"));

        assertThat(invocation.getParentSpanId()).isEqualTo(request.getSpanId());
        assertThat(named("cauce.llm.call")).hasSize(2).allMatch(span -> span.getTraceId().equals(traceId));
        assertThat(named("cauce.tool.call")).hasSize(1).allMatch(span -> span.getTraceId().equals(traceId));
        assertThat(named("cauce.tool.call").get(0).getAttributes().asMap())
                .anySatisfy((key, value) -> assertThat(value).isEqualTo("get_current_time"));
    }

    @Test
    void noSpan_carriesAnAttributeOutsideTheAllowList_orTheMessagesText() throws Exception {
        UUID agentId = createAgent(clientId, clientAuth, List.of("get_current_time"));
        llm.useToolOnFirstCall();

        sendAndProcess(agentId, "persona-1", WORDS);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
            assertThat(named("cauce.invocation")).hasSize(1);
        });
        for (SpanData span : spans.getFinishedSpanItems()) {
            span.getAttributes().forEach((key, value) -> {
                if (span.getName().startsWith("cauce.")) {
                    assertThat(MessageTracing.ALLOWED_KEYS).as("attribute of " + span.getName()).contains(key.getKey());
                }
                assertThat(String.valueOf(value)).as(key.getKey() + " of " + span.getName())
                        .doesNotContain("Zacarías").doesNotContain("Ortu").doesNotContain("699 111 222")
                        .doesNotContain("persona-1");
            });
        }
    }

    private List<SpanData> named(String name) {
        return spans.getFinishedSpanItems().stream().filter(span -> span.getName().equals(name)).toList();
    }
}
