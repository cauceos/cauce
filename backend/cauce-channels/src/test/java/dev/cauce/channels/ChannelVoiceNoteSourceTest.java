package dev.cauce.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelMediaException;
import dev.cauce.channels.spi.MediaChannelAdapter;
import dev.cauce.core.message.MessagePart;
import dev.cauce.orchestration.exception.TranscriptionNotAvailableException;
import dev.cauce.orchestration.exception.VoiceNoteUnavailableException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChannelVoiceNoteSourceTest {

    private static final MessagePart.Audio NOTE = MessagePart.Audio.received("audio/ogg", "AwACAgQ", null, 4);

    private final ChannelConfig config = ChannelConfig.create(UUID.randomUUID(), UUID.randomUUID(), "telegram",
            "token", "hash");
    private ChannelConfigService configs;
    private MediaChannelAdapter telegram;
    private ChannelVoiceNoteSource source;

    @BeforeEach
    void setUp() {
        configs = mock(ChannelConfigService.class);
        telegram = mock(MediaChannelAdapter.class);
        when(telegram.channelType()).thenReturn("telegram");
        when(configs.findActiveConfig(config.id())).thenReturn(Optional.of(config));
        source = new ChannelVoiceNoteSource(configs, List.of(telegram));
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
