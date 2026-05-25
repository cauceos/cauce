package dev.cauce.orchestration.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.orchestration.exception.UnknownModelException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModelContextWindowTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "claude-sonnet-4-7", "claude-opus-4-7", "claude-haiku-4-5",
            "claude-sonnet-4-6", "claude-opus-4-6"})
    void contextWindowFor_whenKnownModel_returns200k(String model) {
        assertThat(ModelContextWindow.contextWindowFor(model)).isEqualTo(200_000);
    }

    @Test
    void contextWindowFor_whenUnknownModel_throwsUnknownModel() {
        assertThatThrownBy(() -> ModelContextWindow.contextWindowFor("gpt-4"))
                .isInstanceOf(UnknownModelException.class);
    }

    @Test
    void contextWindowFor_whenUnknownModel_messageContainsModelName() {
        assertThatThrownBy(() -> ModelContextWindow.contextWindowFor("mistral-large"))
                .isInstanceOf(UnknownModelException.class)
                .hasMessageContaining("mistral-large");
    }
}
