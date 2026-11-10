package dev.cauce.api.webchat;

import java.util.UUID;

/** The ids of an accepted message (202): the reply arrives by polling (ADR 0014, WC4). */
public record SendWebMessageResponse(UUID conversationId, UUID messageId, UUID invocationId) {
}
