package dev.cauce.core.message;

import dev.cauce.core.tool.ToolCall;
import dev.cauce.core.tool.ToolResult;
import java.util.Objects;
import java.util.Optional;

/**
 * One part of a {@link Message}, which is an ordered list of them (ADR 0004, decision 2): a text
 * message is a list of one text part, a tool message a list of one tool part. Closed: a new kind
 * of part is a new case here, and every switch over the parts has to decide it.
 */
public sealed interface MessagePart
        permits MessagePart.Text, MessagePart.Audio, MessagePart.ToolCallPart, MessagePart.ToolResultPart {

    /** The part's kind as the stored form and the content hash v2 name it ({@link MessageParts}). */
    String type();

    /** Plain UTF-8 text, preserved verbatim (never trimmed). */
    record Text(String text) implements MessagePart {

        public static final String TYPE = "text";

        public Text {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public String type() {
            return TYPE;
        }
    }

    /**
     * A voice note (ADR 0012, V1): a reference to bytes Cauce never stores, and the transcript
     * derived from them. What the channel tells on arrival — the media type and the provider's
     * opaque reference — is always there; the rest is learned when the bytes are downloaded and
     * transcribed, and is null until then. The transcript is message text for personal data:
     * here, in the mutable business row, never in the audit chain.
     *
     * @param mediaType the provider's media type, e.g. {@code audio/ogg}
     * @param providerReference the provider's opaque handle to the bytes (Telegram's
     *        {@code file_id}, WhatsApp's media id), meaningful only to the channel's adapter
     * @param sizeBytes the size of the bytes, null until known
     * @param sha256 the lowercase hex SHA-256 of the bytes, null until downloaded
     * @param durationSeconds the length of the note, null until known
     * @param transcript what the note says, null until transcribed
     */
    record Audio(String mediaType, String providerReference, Long sizeBytes, String sha256, Integer durationSeconds,
                 String transcript) implements MessagePart {

        public static final String TYPE = "audio";

        public Audio {
            requireText(mediaType, "mediaType");
            requireText(providerReference, "providerReference");
            if (sizeBytes != null && sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
            if (sha256 != null && !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
            }
            if (durationSeconds != null && durationSeconds < 0) {
                throw new IllegalArgumentException("durationSeconds must not be negative");
            }
        }

        /** A voice note as its channel announced it: nothing downloaded or transcribed yet. */
        public static Audio received(String mediaType, String providerReference, Long sizeBytes,
                                     Integer durationSeconds) {
            return new Audio(mediaType, providerReference, sizeBytes, null, durationSeconds, null);
        }

        /** This note with what its download and transcription learned about it. */
        public Audio transcribed(String transcript, String sha256, long sizeBytes, int durationSeconds) {
            return new Audio(mediaType, providerReference, sizeBytes, sha256, durationSeconds,
                    Objects.requireNonNull(transcript, "transcript"));
        }

        /** What the note says, once transcribed. */
        public Optional<String> transcriptText() {
            return Optional.ofNullable(transcript);
        }

        @Override
        public String type() {
            return TYPE;
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }

    /** The agent's request to run a tool: what {@code tool_content} carried for a TOOL_CALL. */
    record ToolCallPart(ToolCall call) implements MessagePart {

        public static final String TYPE = "tool_call";

        public ToolCallPart {
            Objects.requireNonNull(call, "call");
        }

        @Override
        public String type() {
            return TYPE;
        }
    }

    /** The output of a tool call: what {@code tool_content} carried for a TOOL_RESULT. */
    record ToolResultPart(ToolResult result) implements MessagePart {

        public static final String TYPE = "tool_result";

        public ToolResultPart {
            Objects.requireNonNull(result, "result");
        }

        @Override
        public String type() {
            return TYPE;
        }
    }
}
