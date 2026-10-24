package dev.cauce.orchestration.voice;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessagePart;
import dev.cauce.core.message.MessageRole;
import dev.cauce.llm.speech.Transcriber;
import dev.cauce.llm.speech.Transcription;
import dev.cauce.orchestration.audit.ConductAuditEvents;
import dev.cauce.orchestration.exception.TranscriptionNotAvailableException;
import dev.cauce.orchestration.exception.UnpricedModelException;
import dev.cauce.orchestration.exception.VoiceNoteRejectedException;
import dev.cauce.orchestration.service.ConversationGateway;
import dev.cauce.orchestration.usage.LlmUsageRecord;
import dev.cauce.orchestration.usage.LlmUsageRecorder;
import dev.cauce.orchestration.usage.SpendGuard;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The first step of an invocation whose trigger is a voice note (ADR 0012, V2): before the context
 * is built, each audio part of the trigger without a transcript gets one. Its bytes come from the
 * conversation's channel ({@link VoiceNoteSource}) and are never stored; the transcript, the
 * bytes' hash and size and the note's length go into the part, audited as
 * {@code conduct.message.transcribed} by hash only, and the call is recorded as usage (V56).
 *
 * <p>Runs in the worker, under the invocation's tenant context, so a transient failure of the
 * channel or the transcriber is retried like a model call's, and a note already transcribed by an
 * earlier attempt is not transcribed again. A note over the limits, or with nothing intelligible in
 * it, ends the invocation as {@code VOICE_NOTE_REJECTED} without calling the model. While a note is
 * fetched and heard, its channel shows the person the agent typing ({@link VoiceNoteSource#showTranscribing}):
 * a minute's note takes about twenty seconds on a CPU.
 */
@Service
public class VoiceNoteTranscription {

    private static final Logger log = LoggerFactory.getLogger(VoiceNoteTranscription.class);

    private final ObjectProvider<Transcriber> transcriber;
    private final ObjectProvider<VoiceNoteSource> source;
    private final VoiceNoteProperties limits;
    private final SpendGuard spendGuard;
    private final ConversationGateway conversationGateway;
    private final LlmUsageRecorder usageRecorder;
    private final Clock clock;

    public VoiceNoteTranscription(ObjectProvider<Transcriber> transcriber, ObjectProvider<VoiceNoteSource> source,
                                  VoiceNoteProperties limits, SpendGuard spendGuard,
                                  ConversationGateway conversationGateway, LlmUsageRecorder usageRecorder, Clock clock) {
        this.transcriber = transcriber;
        this.source = source;
        this.limits = limits;
        this.spendGuard = spendGuard;
        this.conversationGateway = conversationGateway;
        this.usageRecorder = usageRecorder;
        this.clock = clock;
    }

    /**
     * Whether this instance reads voice notes: it has a transcriber and a channel that hands over
     * their bytes. Without both, a channel answers a voice note as a message it does not read.
     */
    public boolean available() {
        return transcriber.getIfAvailable() != null && source.getIfAvailable() != null;
    }

    /**
     * {@code messages} (the conversation, oldest first) with the trigger's voice notes transcribed,
     * stored and audited; the same list when there is nothing to transcribe.
     *
     * @throws VoiceNoteRejectedException if a note is over the limits or says nothing
     * @throws TranscriptionNotAvailableException if the instance cannot transcribe it
     * @throws UnpricedModelException if the client has a spend limit and the transcriber no audio price
     */
    public List<Message> transcribePending(Agent agent, Conversation conversation, UUID invocationId,
                                           UUID triggerMessageId, List<Message> messages) {
        List<Message> result = new ArrayList<>(messages);
        for (int i = 0; i < result.size(); i++) {
            Message message = result.get(i);
            if (message.id().equals(triggerMessageId) && message.role() == MessageRole.USER && pending(message)) {
                result.set(i, transcribe(agent, conversation, invocationId, message));
            }
        }
        return result;
    }

    private Message transcribe(Agent agent, Conversation conversation, UUID invocationId, Message message) {
        Transcriber transcriber = this.transcriber.getIfAvailable();
        VoiceNoteSource source = this.source.getIfAvailable();
        if (transcriber == null || source == null) {
            throw new TranscriptionNotAvailableException("A voice note arrived in conversation " + conversation.id()
                    + " and this instance cannot transcribe it: " + (transcriber == null
                    ? "no transcriber is configured (cauce.speech.openai-compatible.enabled)"
                    : "no channel hands over voice notes"));
        }
        if (spendGuard.checkTranscription(agent.tenantId(), transcriber.id(), transcriber.model())
                == SpendGuard.Verdict.UNPRICED_MODEL) {
            throw new UnpricedModelException("Transcriber " + transcriber.id() + "/" + transcriber.model()
                    + " has no audio price in force, and the client has a spend limit");
        }
        UUID channelConfigId = conversation.channelConfigId().orElseThrow(() ->
                new TranscriptionNotAvailableException("Conversation " + conversation.id()
                        + " has a voice note but no channel to fetch it from"));
        List<MessagePart> parts = new ArrayList<>(message.parts());
        for (int p = 0; p < parts.size(); p++) {
            if (!(parts.get(p) instanceof MessagePart.Audio note) || note.transcript() != null) {
                continue;
            }
            MessagePart.Audio transcribed = transcribeNote(agent, conversation, invocationId, channelConfigId,
                    transcriber, source, note);
            parts.set(p, transcribed);
            Message learned = message.withParts(parts);
            message = conversationGateway.rewritePartsAudited(learned, ConductAuditEvents.messageTranscribed(
                    agent.tenantId(), invocationId, agent.id(), learned, transcribed, transcriber.id(),
                    transcriber.model()));
        }
        return message;
    }

    private MessagePart.Audio transcribeNote(Agent agent, Conversation conversation, UUID invocationId,
                                             UUID channelConfigId, Transcriber transcriber, VoiceNoteSource source,
                                             MessagePart.Audio note) {
        long maxBytes = limits.getMaxSize().toBytes();
        long maxSeconds = limits.getMaxDuration().toSeconds();
        if (note.durationSeconds() != null && note.durationSeconds() > maxSeconds) {
            throw new VoiceNoteRejectedException("The voice note lasts " + note.durationSeconds()
                    + " s, over the limit of " + maxSeconds + " s");
        }
        if (note.sizeBytes() != null && note.sizeBytes() > maxBytes) {
            throw new VoiceNoteRejectedException("The voice note is " + note.sizeBytes()
                    + " bytes, over the limit of " + maxBytes);
        }
        byte[] audio;
        Transcription transcription;
        try (VoiceNoteSource.Activity typing = source.showTranscribing(channelConfigId, conversation.identityId())) {
            audio = source.fetch(channelConfigId, note, maxBytes);
            if (audio.length > maxBytes) {
                throw new VoiceNoteRejectedException("The voice note is " + audio.length
                        + " bytes, over the limit of " + maxBytes);
            }
            transcription = transcriber.transcribe(audio, note.mediaType(), null);
        }
        int seconds = transcription.duration().orElse(note.durationSeconds() == null ? 0 : note.durationSeconds());
        if (seconds == 0) {
            log.warn("The transcriber reported no duration for a voice note of conversation {}: usage recorded as 0 s",
                    conversation.id());
        }
        // Billed whatever comes next: the provider transcribed it.
        usageRecorder.record(LlmUsageRecord.transcription(agent.tenantId(), agent.id(), conversation.id(),
                invocationId, transcriber.id(), transcriber.model(), seconds, clock.instant()));
        if (seconds > maxSeconds) {
            throw new VoiceNoteRejectedException("The voice note lasts " + seconds + " s, over the limit of "
                    + maxSeconds + " s");
        }
        if (transcription.text().isBlank()) {
            throw new VoiceNoteRejectedException("The voice note was transcribed to nothing");
        }
        return note.transcribed(transcription.text(), sha256(audio), audio.length, seconds);
    }

    private static boolean pending(Message message) {
        return message.parts().stream().anyMatch(part -> part instanceof MessagePart.Audio audio
                && audio.transcript() == null);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
