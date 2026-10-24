package dev.cauce.orchestration.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform settings of the fallback reply ({@code cauce.orchestration.fallback.*}, ADR 0005,
 * C3): the text the interlocutor receives when an invocation fails for good and the agent has
 * none of its own. It must never carry internal detail; it is the same text for every failure but
 * one: a voice note that could not be read is answered with {@link #getVoiceNoteReply()}, which
 * asks for it in writing or shorter (ADR 0012, V5).
 */
@ConfigurationProperties(prefix = "cauce.orchestration.fallback")
public class FallbackReplyProperties {

    /** Platform default of {@link #getDefaultReply()}. */
    public static final String DEFAULT_REPLY =
            "Sorry, I could not answer your message this time. Please try again later.";

    /** Platform default of {@link #getVoiceNoteReply()}. */
    public static final String DEFAULT_VOICE_NOTE_REPLY =
            "Sorry, I could not make out that voice note. Could you write it, or send a shorter one?";

    private String defaultReply = DEFAULT_REPLY;

    private String voiceNoteReply = DEFAULT_VOICE_NOTE_REPLY;

    public String getDefaultReply() {
        return defaultReply;
    }

    public void setDefaultReply(String defaultReply) {
        if (defaultReply == null || defaultReply.isBlank()) {
            throw new IllegalArgumentException("cauce.orchestration.fallback.default-reply must not be blank");
        }
        this.defaultReply = defaultReply.strip();
    }

    /**
     * The text for a voice note that could not be read, when the agent has no reply of its own to a
     * message it cannot read ({@code unsupported_message_reply}).
     */
    public String getVoiceNoteReply() {
        return voiceNoteReply;
    }

    public void setVoiceNoteReply(String voiceNoteReply) {
        if (voiceNoteReply == null || voiceNoteReply.isBlank()) {
            throw new IllegalArgumentException("cauce.orchestration.fallback.voice-note-reply must not be blank");
        }
        this.voiceNoteReply = voiceNoteReply.strip();
    }
}
