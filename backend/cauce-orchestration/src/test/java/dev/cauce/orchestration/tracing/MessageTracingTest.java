package dev.cauce.orchestration.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MessageTracingTest {

    private final MessageTracing tracing = MessageTracing.noop();

    @Test
    void tag_aKeyOutsideTheAllowList_isRejected_evenWhenNothingIsRecorded() {
        try (MessageTracing.TracedSpan span = tracing.childSpan("cauce.llm.call")) {
            assertThatThrownBy(() -> span.tag("content", "Mesa para 4, soy Ana"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("content")
                    .hasMessageNotContaining("Ana");
        }
    }

    @Test
    void tag_anAllowedKey_isAccepted() {
        try (MessageTracing.TracedSpan span = tracing.continueTrace(null, "cauce.invocation")) {
            span.tag("invocation_id", "0192f000-0000-7000-8000-000000000000").tag("round", 1);
        }
    }

    @Test
    void currentTraceParent_withoutATracer_isEmpty() {
        assertThat(tracing.currentTraceParent()).isEmpty();
    }

    @Test
    void allowList_holdsNoKeyThatNamesContent() {
        assertThat(MessageTracing.ALLOWED_KEYS)
                .noneMatch(key -> key.contains("content") || key.contains("text") || key.contains("prompt")
                        || key.contains("argument") || key.contains("identity"));
    }
}
