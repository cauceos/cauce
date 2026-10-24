package dev.cauce.orchestration.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.identity.Identity;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessagePart;
import dev.cauce.core.message.MessageRole;
import dev.cauce.llm.speech.Transcriber;
import dev.cauce.llm.speech.Transcription;
import dev.cauce.orchestration.exception.TranscriptionNotAvailableException;
import dev.cauce.orchestration.exception.UnpricedModelException;
import dev.cauce.orchestration.exception.VoiceNoteRejectedException;
import dev.cauce.orchestration.service.ConversationGateway;
import dev.cauce.orchestration.usage.LlmUsageRecord;
import dev.cauce.orchestration.usage.LlmUsageRecorder;
import dev.cauce.orchestration.usage.SpendGuard;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class VoiceNoteTranscriptionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC);
    private static final byte[] AUDIO = "OggS-voice".getBytes(StandardCharsets.UTF_8);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID invocationId = UUID.randomUUID();
    private final Agent agent = Agent.create(tenantId, "Camarero", "p", "anthropic", "claude-sonnet-5-5");
    private final Conversation conversation = Conversation.start(agent.id(),
            Identity.create(tenantId, "telegram", IdentityKind.PROVIDER_USER_ID, "987654321"), UUID.randomUUID());

    private Transcriber transcriber;
    private VoiceNoteSource source;
    private SpendGuard spendGuard;
    private ConversationGateway gateway;
    private LlmUsageRecorder usage;
    private VoiceNoteTranscription voiceNotes;

    @BeforeEach
    void setUp() {
        transcriber = mock(Transcriber.class);
        when(transcriber.id()).thenReturn("whisper");
        when(transcriber.model()).thenReturn("small");
        source = mock(VoiceNoteSource.class);
        spendGuard = mock(SpendGuard.class);
        when(spendGuard.checkTranscription(any(), any(), any())).thenReturn(SpendGuard.Verdict.ALLOWED);
        gateway = mock(ConversationGateway.class);
        when(gateway.rewritePartsAudited(any(), any())).thenAnswer(call -> call.getArgument(0));
        usage = mock(LlmUsageRecorder.class);
        voiceNotes = service(transcriber, source);
    }

    private VoiceNoteTranscription service(Transcriber transcriber, VoiceNoteSource source) {
        return new VoiceNoteTranscription(provider(transcriber), provider(source), new VoiceNoteProperties(),
                spendGuard, gateway, usage, CLOCK);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private Message voiceNote(Integer seconds) {
        return Message.fromParts(conversation.id(), MessageRole.USER,
                List.of(MessagePart.Audio.received("audio/ogg", "AwACAgQ", null, seconds)));
    }

    @Test
    void transcribePending_transcribesTheTriggersNote_storesAuditsAndBillsIt() {
        Message older = Message.from(conversation.id(), MessageRole.USER, "Hola");
        Message note = voiceNote(4);
        when(source.fetch(eq(conversation.channelConfigId().orElseThrow()), any(), anyLong())).thenReturn(AUDIO);
        when(transcriber.transcribe(AUDIO, "audio/ogg", null)).thenReturn(new Transcription("Mesa para cuatro", 5));

        List<Message> result = voiceNotes.transcribePending(agent, conversation, invocationId, note.id(),
                List.of(older, note));

        assertThat(result.get(0)).isSameAs(older);
        MessagePart.Audio heard = (MessagePart.Audio) result.get(1).parts().get(0);
        assertThat(heard.transcript()).isEqualTo("Mesa para cuatro");
        assertThat(heard.durationSeconds()).isEqualTo(5);
        assertThat(heard.sizeBytes()).isEqualTo(AUDIO.length);
        assertThat(heard.sha256()).hasSize(64);
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(gateway).rewritePartsAudited(any(), audit.capture());
        assertThat(audit.getValue().eventType()).isEqualTo("conduct.message.transcribed");
        assertThat(audit.getValue().payload().values()).doesNotContain("Mesa para cuatro");
        ArgumentCaptor<LlmUsageRecord> billed = ArgumentCaptor.forClass(LlmUsageRecord.class);
        verify(usage).record(billed.capture());
        assertThat(billed.getValue().audioSeconds()).isEqualTo(5);
        assertThat(billed.getValue().provider()).isEqualTo("whisper");
    }

    @Test
    void transcribePending_withNothingToTranscribe_touchesNothing() {
        Message text = Message.from(conversation.id(), MessageRole.USER, "Hola");
        Message alreadyHeard = Message.fromParts(conversation.id(), MessageRole.USER, List.of(
                MessagePart.Audio.received("audio/ogg", "r", null, 4).transcribed("Hola", "a".repeat(64), 10, 4)));

        assertThat(voiceNotes.transcribePending(agent, conversation, invocationId, text.id(), List.of(text)))
                .containsExactly(text);
        assertThat(voiceNotes.transcribePending(agent, conversation, invocationId, alreadyHeard.id(),
                List.of(alreadyHeard))).containsExactly(alreadyHeard);
        verifyNoInteractions(source, gateway, usage);
    }

    @Test
    void transcribePending_aNoteTheChannelSaysIsTooLong_isRejected_beforeItIsFetched() {
        Message note = voiceNote(121);

        assertThatThrownBy(() -> voiceNotes.transcribePending(agent, conversation, invocationId, note.id(), List.of(note)))
                .isInstanceOf(VoiceNoteRejectedException.class).hasMessageContaining("121 s");
        verifyNoInteractions(source, usage);
    }

    @Test
    void transcribePending_aNoteTheTranscriberFindsTooLong_isBilled_andRejected() {
        Message note = voiceNote(null);
        when(source.fetch(any(), any(), anyLong())).thenReturn(AUDIO);
        when(transcriber.transcribe(any(), any(), any())).thenReturn(new Transcription("Largo", 150));

        assertThatThrownBy(() -> voiceNotes.transcribePending(agent, conversation, invocationId, note.id(), List.of(note)))
                .isInstanceOf(VoiceNoteRejectedException.class);
        verify(usage).record(any());
        verify(gateway, never()).rewritePartsAudited(any(), any());
    }

    @Test
    void transcribePending_aNoteWithNothingIntelligible_isRejected() {
        Message note = voiceNote(3);
        when(source.fetch(any(), any(), anyLong())).thenReturn(AUDIO);
        when(transcriber.transcribe(any(), any(), any())).thenReturn(new Transcription("  ", 3));

        assertThatThrownBy(() -> voiceNotes.transcribePending(agent, conversation, invocationId, note.id(), List.of(note)))
                .isInstanceOf(VoiceNoteRejectedException.class).hasMessageContaining("nothing");
    }

    @Test
    void transcribePending_withoutATranscriber_isTheInstancesSetup() {
        Message note = voiceNote(3);

        assertThat(service(null, source).available()).isFalse();
        assertThatThrownBy(() -> service(null, source).transcribePending(agent, conversation, invocationId, note.id(),
                List.of(note)))
                .isInstanceOf(TranscriptionNotAvailableException.class).hasMessageContaining("no transcriber");
        assertThat(voiceNotes.available()).isTrue();
    }

    @Test
    void transcribePending_underASpendLimit_needsTheTranscribersAudioPrice() {
        Message note = voiceNote(3);
        when(spendGuard.checkTranscription(tenantId, "whisper", "small")).thenReturn(SpendGuard.Verdict.UNPRICED_MODEL);

        assertThatThrownBy(() -> voiceNotes.transcribePending(agent, conversation, invocationId, note.id(), List.of(note)))
                .isInstanceOf(UnpricedModelException.class);
        verifyNoInteractions(source);
    }
}
