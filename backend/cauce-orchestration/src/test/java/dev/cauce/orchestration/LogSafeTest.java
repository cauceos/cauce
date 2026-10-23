package dev.cauce.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.llm.exception.LlmInvalidRequestException;
import dev.cauce.orchestration.exception.MaxToolIterationsExceededException;
import org.junit.jupiter.api.Test;

class LogSafeTest {

    @Test
    void describe_aProviderFailure_givesItsTypeProviderAndModel_neverItsDetail() {
        LlmInvalidRequestException e = new LlmInvalidRequestException("anthropic", "claude-sonnet-5-5",
                "messages.0.content: Mesa para 4, soy Ana, 600 123 456");

        assertThat(LogSafe.describe(e))
                .isEqualTo("LlmInvalidRequestException[provider=anthropic, model=claude-sonnet-5-5]")
                .doesNotContain("Ana").doesNotContain("600");
    }

    @Test
    void describe_oneOfCaucesOwnExceptions_keepsItsMessage_whichNamesIds() {
        assertThat(LogSafe.describe(new MaxToolIterationsExceededException("cap of 10 reached")))
                .isEqualTo("MaxToolIterationsExceededException: cap of 10 reached");
    }

    @Test
    void describe_aForeignException_givesItsTypeOnly() {
        assertThat(LogSafe.describe(new IllegalStateException("Key (value)=(600 123 456) already exists")))
                .isEqualTo("IllegalStateException");
    }

    @Test
    void type_isTheTypeOnly_evenForCaucesOwn() {
        assertThat(LogSafe.type(new MaxToolIterationsExceededException("tool said: Ana, 600 123 456")))
                .isEqualTo("MaxToolIterationsExceededException");
    }
}
