package dev.cauce.orchestration.voice;

import dev.cauce.core.message.MessagePart;
import java.util.UUID;

/**
 * Where a voice note's bytes come from (ADR 0012, V1): the channel it arrived through, which holds
 * them under the provider's reference. A port, like {@code AgentReplyDispatcher}: the orchestrator
 * asks, and the channels module answers with the adapter of the conversation's channel config, so
 * orchestration never knows a provider. Implementations run under the invocation's tenant context.
 */
public interface VoiceNoteSource {

    /**
     * The bytes of {@code note}, received through {@code channelConfigId}; never more than
     * {@code maxBytes}.
     *
     * @throws dev.cauce.orchestration.exception.VoiceNoteRejectedException if the note is larger
     * @throws dev.cauce.orchestration.exception.TranscriptionNotAvailableException if the channel
     *     cannot hand over its media
     * @throws dev.cauce.orchestration.exception.VoiceNoteUnavailableException if the channel does
     *     not hand them over: retried with backoff when transient, each attempt fetching anew
     */
    byte[] fetch(UUID channelConfigId, MessagePart.Audio note, long maxBytes);

    /**
     * Shows the interlocutor {@code identityId}, in channel config {@code channelConfigId}, that their
     * voice note is being heard — the channel's own "typing" indicator, never a message — until the
     * returned activity is closed. Best-effort: never throws, and a channel without such an indicator
     * shows nothing.
     */
    default Activity showTranscribing(UUID channelConfigId, UUID identityId) {
        return Activity.NONE;
    }

    /** An indicator being shown; closing it stops it. */
    interface Activity extends AutoCloseable {

        Activity NONE = () -> { };

        @Override
        void close();
    }
}
