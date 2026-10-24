package dev.cauce.llm.openai;

import dev.cauce.llm.speech.Transcriber;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the instance's transcriber (ADR 0012, V3) when {@code cauce.speech.openai-compatible.enabled}
 * is true: one OpenAI-compatible endpoint, a local Whisper by default. Without it no {@link Transcriber}
 * exists, and voice notes are answered as messages Cauce does not read.
 */
@Configuration
@EnableConfigurationProperties(OpenAiCompatibleTranscriptionProperties.class)
public class OpenAiCompatibleTranscriptionConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "cauce.speech.openai-compatible", name = "enabled", havingValue = "true")
    public Transcriber openAiCompatibleTranscriber(OpenAiCompatibleTranscriptionProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.getTimeout()).build();
        return new OpenAiCompatibleTranscriber(properties.getProviderId(), properties.getModel(),
                properties.getBaseUrl(), properties.getApiKey(), properties.getTimeout(), httpClient,
                properties.getLanguage());
    }
}
