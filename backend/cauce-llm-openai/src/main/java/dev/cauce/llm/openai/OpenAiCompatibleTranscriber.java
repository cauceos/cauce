package dev.cauce.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.llm.exception.LlmProviderException;
import dev.cauce.llm.exception.LlmTimeoutException;
import dev.cauce.llm.speech.Transcriber;
import dev.cauce.llm.speech.Transcription;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * The transcription SPI over an OpenAI-compatible {@code POST {baseUrl}/audio/transcriptions}
 * (ADR 0012, V3): a multipart upload of the audio with the model, asking for {@code verbose_json}
 * so the answer carries the audio's duration. The first server it targets is a local Whisper
 * (speaches, in the quickstart's optional profile); a hosted provider with the same API is the
 * same adapter with another base URL and key. Errors map as the LLM adapter's do
 * ({@link OpenAiErrorMapper}), so the worker retries a transient failure and fails a permanent one.
 */
public final class OpenAiCompatibleTranscriber implements Transcriber {

    private final String providerId;
    private final String model;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final String defaultLanguage;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiErrorMapper errorMapper = new OpenAiErrorMapper(objectMapper);

    public OpenAiCompatibleTranscriber(String providerId, String model, String baseUrl, String apiKey,
                                       Duration timeout, HttpClient httpClient) {
        this(providerId, model, baseUrl, apiKey, timeout, httpClient, null);
    }

    /** As the other, with the language the server is told when a call hints none ({@code null}: detect it). */
    public OpenAiCompatibleTranscriber(String providerId, String model, String baseUrl, String apiKey,
                                       Duration timeout, HttpClient httpClient, String defaultLanguage) {
        this.defaultLanguage = defaultLanguage == null || defaultLanguage.isBlank() ? null : defaultLanguage;
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.model = Objects.requireNonNull(model, "model");
        this.endpoint = URI.create(stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl")) + "/audio/transcriptions");
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public String id() {
        return providerId;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Transcription transcribe(byte[] audio, String mediaType, String languageHint) {
        Objects.requireNonNull(audio, "audio");
        String boundary = "cauce-" + UUID.randomUUID();
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, audio, mediaType,
                        languageHint == null ? defaultLanguage : languageHint)));
        if (apiKey != null) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new LlmTimeoutException(providerId, model, "Transcription timed out after " + timeout, e);
        } catch (IOException e) {
            throw new LlmProviderException(providerId, model, "HTTP call to " + providerId + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmProviderException(providerId, model, "Interrupted during " + providerId + " call", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw errorMapper.toException(response.statusCode(), response.body(), providerId, model);
        }
        try {
            JsonNode body = objectMapper.readTree(response.body());
            JsonNode text = body.path("text");
            if (!text.isTextual()) {
                throw new LlmProviderException(providerId, model, "Transcription response has no text");
            }
            JsonNode duration = body.path("duration");
            Integer seconds = duration.isNumber() ? (int) Math.ceil(duration.asDouble()) : null;
            return new Transcription(text.asText().strip(), seconds);
        } catch (IOException e) {
            throw new LlmProviderException(providerId, model, "Failed to parse transcription response", e);
        }
    }

    /** The form: the audio as {@code file}, the {@code model}, {@code verbose_json}, and the language if hinted. */
    private byte[] multipart(String boundary, byte[] audio, String mediaType, String languageHint) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(audio.length + 1024);
        field(body, boundary, "model", model);
        field(body, boundary, "response_format", "verbose_json");
        if (languageHint != null && !languageHint.isBlank()) {
            field(body, boundary, "language", languageHint);
        }
        write(body, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"voice-note" + extension(mediaType) + "\"\r\n"
                + "Content-Type: " + (mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType)
                + "\r\n\r\n");
        body.writeBytes(audio);
        write(body, "\r\n--" + boundary + "--\r\n");
        return body.toByteArray();
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) {
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream body, String text) {
        body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A file name extension servers that sniff by name recognise: Telegram and WhatsApp send Ogg Opus. */
    private static String extension(String mediaType) {
        if (mediaType == null) {
            return "";
        }
        String type = mediaType.toLowerCase(java.util.Locale.ROOT);
        if (type.contains("ogg") || type.contains("opus")) {
            return ".ogg";
        }
        if (type.contains("mpeg") || type.contains("mp3")) {
            return ".mp3";
        }
        if (type.contains("mp4") || type.contains("m4a") || type.contains("aac")) {
            return ".m4a";
        }
        if (type.contains("wav")) {
            return ".wav";
        }
        return "";
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
