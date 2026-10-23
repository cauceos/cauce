package dev.cauce.orchestration.context;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class ModelContextWindowTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "claude-fable-5-1", "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5",
            "claude-fable-5", "claude-opus-5", "claude-sonnet-5",
            "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6", "claude-sonnet-4-6"})
    void contextWindowFor_when1mModel_returns1m(String model) {
        assertThat(ModelContextWindow.contextWindowFor(model)).isEqualTo(1_000_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-sonnet-4-7", "claude-haiku-4-5", "claude-haiku-4-5-20251001"})
    void contextWindowFor_when200kModel_returns200k(String model) {
        assertThat(ModelContextWindow.contextWindowFor(model)).isEqualTo(200_000);
    }

    @Test
    void contextWindowFor_aModelALocalServerServes_isNotClaimedByTheRegistry() {
        // Its window is the server's setting, reported by its provider: never the model's 32K here.
        assertThat(ModelContextWindow.contextWindowFor("qwen2.5:3b"))
                .isEqualTo(ModelContextWindow.DEFAULT_CONTEXT_WINDOW);
    }

    @Test
    void contextWindowFor_whenUnknownModel_returnsConservativeDefault() {
        assertThat(ModelContextWindow.contextWindowFor("gpt-4o"))
                .isEqualTo(ModelContextWindow.DEFAULT_CONTEXT_WINDOW)
                .isEqualTo(16_384);
    }

    @Test
    void contextWindowFor_whenUnknownModel_warnsOncePerModelId() {
        Logger logger = (Logger) LoggerFactory.getLogger(ModelContextWindow.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String unknown = "warn-once-probe-model";
            ModelContextWindow.contextWindowFor(unknown);
            ModelContextWindow.contextWindowFor(unknown); // a second lookup must not warn again

            List<ILoggingEvent> warns = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .filter(event -> event.getFormattedMessage().contains(unknown))
                    .toList();
            assertThat(warns).hasSize(1);
            assertThat(warns.get(0).getFormattedMessage()).contains("16384");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
