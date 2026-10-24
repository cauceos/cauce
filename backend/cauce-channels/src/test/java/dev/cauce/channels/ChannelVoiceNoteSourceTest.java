package dev.cauce.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelDeliveryException;
import dev.cauce.channels.spi.ChannelMediaException;
import dev.cauce.channels.spi.MediaChannelAdapter;
import dev.cauce.core.identity.Identity;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.message.MessagePart;
import dev.cauce.orchestration.voice.VoiceNoteSource;
import dev.cauce.orchestration.exception.TranscriptionNotAvailableException;
import dev.cauce.orchestration.exception.VoiceNoteUnavailableException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChannelVoiceNoteSourceTest {

    private static final MessagePart.Audio NOTE = MessagePart.Audio.received("audio/ogg", "AwACAgQ", null, 4);

    private final ChannelConfig config = ChannelConfig.create(UUID.randomUUID(), UUID.randomUUID(), "telegram",
            "token", "hash");
    private final Identity person = Identity.create(config.tenantId(), "telegram", IdentityKind.PROVIDER_USER_ID,
            "987654321");
    private ChannelConfigService configs;
    private InterlocutorLookup interlocutors;
    private MediaChannelAdapter telegram;
    private ChannelVoiceNoteSource source;

    @BeforeEach
    void setUp() {
        configs = mock(ChannelConfigService.class);
        telegram = mock(MediaChannelAdapter.class);
        when(telegram.channelType()).thenReturn("telegram");
        when(configs.findActiveConfig(config.id())).thenReturn(Optional.of(config));
        interlocutors = mock(InterlocutorLookup.class);
        when(interlocutors.find(person.id())).thenReturn(Optional.of(person));
        source = new ChannelVoiceNoteSource(configs, interlocutors, List.of(telegram));
    }

    @AfterEach
    void tearDown() {
        source.destroy();
    }

    @Test
    void showTranscribing_showsThePersonTyping_untilItIsClosed() {
        VoiceNoteSource.Activity typing = source.showTranscribing(config.id(), person.id());

        verify(telegram, timeout(2000)).showTyping(config, IdentityKind.PROVIDER_USER_ID, "987654321");
        typing.close();
    }

    @Test
    void showTranscribing_aRefusedIndicator_neverReachesTheWorker() {
        doThrow(new ChannelDeliveryException("Telegram sendChatAction returned HTTP 403")).when(telegram)
                .showTyping(any(), any(), any());

        VoiceNoteSource.Activity typing = source.showTranscribing(config.id(), person.id());

        // Refused once, it is shown again at the next refresh: the repetition survives the failure.
        verify(telegram, timeout(2000 + ChannelVoiceNoteSource.TYPING_REFRESH.toMillis()).times(2))
                .showTyping(config, IdentityKind.PROVIDER_USER_ID, "987654321");
        typing.close();
    }

    @Test
    void showTranscribing_throughAConfigThatIsNoLongerActive_showsNothing() {
        when(configs.findActiveConfig(config.id())).thenReturn(Optional.empty());

        assertThat(source.showTranscribing(config.id(), person.id())).isSameAs(VoiceNoteSource.Activity.NONE);
        verify(telegram, never()).showTyping(any(), any(), any());
    }

    @Test
    void fetch_asksTheAdapterOfTheConversationsChannel() {
        when(telegram.download("AwACAgQ", config, 100)).thenReturn(new byte[] {1, 2, 3});

        assertThat(source.fetch(config.id(), NOTE, 100)).containsExactly(1, 2, 3);
    }

    @Test
    void fetch_tellsTheWorkerWhetherAMediaFailureIsWorthRetrying() {
        when(telegram.download("AwACAgQ", config, 100))
                .thenThrow(ChannelMediaException.ofStatus(502, "Telegram file download returned HTTP 502"))
                .thenThrow(ChannelMediaException.ofStatus(400, "Telegram getFile returned HTTP 400"));

        assertThatThrownBy(() -> source.fetch(config.id(), NOTE, 100))
                .isInstanceOf(VoiceNoteUnavailableException.class)
                .satisfies(e -> assertThat(((VoiceNoteUnavailableException) e).isTransient()).isTrue());
        assertThatThrownBy(() -> source.fetch(config.id(), NOTE, 100))
                .isInstanceOf(VoiceNoteUnavailableException.class)
                .satisfies(e -> assertThat(((VoiceNoteUnavailableException) e).isTransient()).isFalse());
    }

    @Test
    void fetch_throughAConfigThatIsNoLongerActive_cannotBeDone() {
        when(configs.findActiveConfig(config.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> source.fetch(config.id(), NOTE, 100))
                .isInstanceOf(TranscriptionNotAvailableException.class);
    }
}
