package dev.cauce.api.webchat;

/**
 * Body of {@code POST /web/v1/{publicKey}/messages} (ADR 0014, W3): what the person wrote and, to start
 * over, {@code new_thread}. Bounds are checked by the service, so the widget gets one error shape.
 */
public record SendWebMessageRequest(String content, Boolean newThread) {
}
