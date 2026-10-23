package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The quickstart's compose file gives the app the context window Ollama serves: Ollama's
 * OpenAI-compatible endpoint takes no per-request context length, so the app sizes every
 * request to the window it is told, and a window larger than the server's is history cut
 * silently. Both must come from the same expression.
 */
class QuickstartComposeTest {

    private static final Path COMPOSE = Path.of("..", "..", "docker-compose.yml");

    private static final Pattern OLLAMA_SERVES =
            Pattern.compile("(?m)^\\s+OLLAMA_CONTEXT_LENGTH:\\s*(\\S+)\\s*$");
    private static final Pattern APP_ASSUMES =
            Pattern.compile("(?m)^\\s+CAUCE_LLM_OPENAICOMPATIBLE_PROVIDERS_OLLAMA_CONTEXTWINDOW:\\s*(\\S+)\\s*$");

    @Test
    void ollamaContextLength_theAppAssumesWhatTheOllamaServiceServes() throws IOException {
        String compose = Files.readString(COMPOSE);

        String served = single(OLLAMA_SERVES, compose);
        String assumed = single(APP_ASSUMES, compose);

        assertThat(assumed).as("the app's Ollama window must be the ollama service's context length")
                .isEqualTo(served);
        assertThat(served).as("Ollama's own default, when nothing raises it")
                .isEqualTo("${OLLAMA_CONTEXT_LENGTH:-4096}");
    }

    private static String single(Pattern pattern, String compose) {
        Matcher matcher = pattern.matcher(compose);
        assertThat(matcher.find()).as("%s in docker-compose.yml", pattern).isTrue();
        String value = matcher.group(1);
        assertThat(matcher.find()).as("%s set once in docker-compose.yml", pattern).isFalse();
        return value;
    }
}
