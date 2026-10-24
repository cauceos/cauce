package dev.cauce.llm.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.cauce.llm.exception.LlmAuthenticationException;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.llm.exception.LlmRateLimitException;
import dev.cauce.llm.exception.LlmTimeoutException;
import dev.cauce.llm.speech.Transcription;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

@WireMockTest
class OpenAiCompatibleTranscriberTest {

    private static final byte[] OGG = "OggS-fake-opus".getBytes(StandardCharsets.UTF_8);

    private OpenAiCompatibleTranscriber against(WireMockRuntimeInfo wm, String apiKey, Duration timeout) {
        return new OpenAiCompatibleTranscriber("whisper", "Systran/faster-whisper-small",
                wm.getHttpBaseUrl() + "/v1/", apiKey, timeout, HttpClient.newHttpClient());
    }

    @Test
    void transcribe_uploadsTheAudioWithTheModel_andReadsTheTextAndTheDurationRoundedUp(WireMockRuntimeInfo wm) {
        stubFor(post("/v1/audio/transcriptions").willReturn(okJson("""
                {"text": " Quería mesa para cuatro. ", "duration": 4.2, "language": "es"}
                """)));

        Transcription transcription = against(wm, "", Duration.ofSeconds(5)).transcribe(OGG, "audio/ogg", "es");

        assertThat(transcription.text()).isEqualTo("Quería mesa para cuatro.");
        assertThat(transcription.duration()).hasValue(5);
        verify(postRequestedFor(urlEqualTo("/v1/audio/transcriptions"))
                .withHeader("Content-Type", containing("multipart/form-data; boundary="))
                .withHeader("Authorization", absent())
                .withRequestBody(containing("name=\"model\"\r\n\r\nSystran/faster-whisper-small"))
                .withRequestBody(containing("name=\"response_format\"\r\n\r\nverbose_json"))
                .withRequestBody(containing("name=\"language\"\r\n\r\nes"))
                .withRequestBody(containing("filename=\"voice-note.ogg\"\r\nContent-Type: audio/ogg"))
                .withRequestBody(containing("OggS-fake-opus")));
    }

    @Test
    void transcribe_sendsTheKeyOfAHostedProvider_andNoLanguageWhenNoneIsHinted(WireMockRuntimeInfo wm) {
        stubFor(post("/v1/audio/transcriptions").willReturn(okJson("{\"text\": \"Hola\"}")));

        Transcription transcription = against(wm, "sk-test", Duration.ofSeconds(5)).transcribe(OGG, "audio/ogg", null);

        assertThat(transcription.duration()).isEmpty();
        verify(postRequestedFor(urlEqualTo("/v1/audio/transcriptions"))
                .withHeader("Authorization", equalTo("Bearer sk-test")));
        assertThat(wm.getWireMock().getServeEvents().get(0).getRequest().getBodyAsString()).doesNotContain("\"language\"");
    }

    @Test
    void transcribe_mapsErrorsAsTheLlmAdapterDoes(WireMockRuntimeInfo wm) {
        OpenAiCompatibleTranscriber transcriber = against(wm, "", Duration.ofSeconds(5));

        stubFor(post("/v1/audio/transcriptions").willReturn(aResponse().withStatus(401).withBody("{\"error\":\"no\"}")));
        assertThatThrownBy(() -> transcriber.transcribe(OGG, "audio/ogg", null)).isInstanceOf(LlmAuthenticationException.class);

        stubFor(post("/v1/audio/transcriptions").willReturn(aResponse().withStatus(429)));
        assertThatThrownBy(() -> transcriber.transcribe(OGG, "audio/ogg", null)).isInstanceOf(LlmRateLimitException.class);

        stubFor(post("/v1/audio/transcriptions").willReturn(okJson("{\"segments\": []}")));
        assertThatThrownBy(() -> transcriber.transcribe(OGG, "audio/ogg", null))
                .isInstanceOf(LlmProviderException.class).hasMessageContaining("no text");
    }

    @Test
    void transcribe_aServerTooSlow_isATimeout(WireMockRuntimeInfo wm) {
        stubFor(post("/v1/audio/transcriptions").willReturn(okJson("{\"text\": \"Hola\"}").withFixedDelay(1500)));

        assertThatThrownBy(() -> against(wm, "", Duration.ofMillis(300)).transcribe(OGG, "audio/ogg", null))
                .isInstanceOf(LlmTimeoutException.class);
    }
}
