package dev.cauce.channels.webchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Asks Cloudflare's {@code siteverify} whether a Turnstile token is valid (ADR 0014, WC2): a form POST
 * with the secret and the token, and never {@code remoteip}: the visitor's IP is not sent anywhere
 * (ADR 0014, amendment). An answer other than a JSON {@code success} is a refusal; no answer at all is
 * {@link TurnstileUnavailableException}, so a session never opens unverified.
 */
public class CloudflareTurnstileVerifier implements TurnstileVerifier {

    private final HttpClient httpClient;
    private final WebChatProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CloudflareTurnstileVerifier(HttpClient httpClient, WebChatProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    @Override
    public boolean verify(String secret, String token) {
        if (token == null || token.isBlank() || token.length() > 2048) {
            return false;
        }
        String form = "secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8)
                + "&response=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getTurnstileVerifyUrl()))
                .timeout(properties.getTurnstileTimeout())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new TurnstileUnavailableException("Turnstile could not be reached", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TurnstileUnavailableException("Interrupted while asking Turnstile", e);
        }
        if (response.statusCode() >= 500) {
            throw new TurnstileUnavailableException("Turnstile answered " + response.statusCode(), null);
        }
        try {
            JsonNode body = objectMapper.readTree(response.body());
            return response.statusCode() == 200 && body.path("success").asBoolean(false);
        } catch (IOException e) {
            return false;
        }
    }
}
