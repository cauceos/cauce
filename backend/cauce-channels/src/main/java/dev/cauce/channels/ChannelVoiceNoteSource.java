package dev.cauce.channels;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelMediaException;
import dev.cauce.channels.spi.MediaChannelAdapter;
import dev.cauce.core.message.MessagePart;
import dev.cauce.orchestration.exception.TranscriptionNotAvailableException;
import dev.cauce.orchestration.exception.VoiceNoteUnavailableException;
import dev.cauce.orchestration.voice.VoiceNoteSource;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The channels' side of {@link VoiceNoteSource} (ADR 0012, V1): resolves the conversation's channel
 * config under the invocation's tenant context (RLS) and asks its channel's
 * {@link MediaChannelAdapter} for the note's bytes. Present whenever the channels module is, so a
 * voice note is read only where a channel can hand it over.
 */
@Component
public class ChannelVoiceNoteSource implements VoiceNoteSource {

    private final ChannelConfigService channelConfigService;
    private final List<MediaChannelAdapter> adapters;

    public ChannelVoiceNoteSource(ChannelConfigService channelConfigService, List<MediaChannelAdapter> adapters) {
        this.channelConfigService = channelConfigService;
        this.adapters = List.copyOf(adapters);
    }

    @Override
    public byte[] fetch(UUID channelConfigId, MessagePart.Audio note, long maxBytes) {
        ChannelConfig config = channelConfigService.findActiveConfig(channelConfigId).orElseThrow(() ->
                new TranscriptionNotAvailableException("Channel config " + channelConfigId
                        + " is not ACTIVE: the voice note cannot be fetched"));
        MediaChannelAdapter adapter = adapters.stream()
                .filter(candidate -> candidate.channelType().equals(config.channelType()))
                .findFirst()
                .orElseThrow(() -> new TranscriptionNotAvailableException("Channel '" + config.channelType()
                        + "' does not hand over voice notes"));
        try {
            return adapter.download(note.providerReference(), config, maxBytes);
        } catch (ChannelMediaException e) {
            // The worker retries a transient failure with backoff; each attempt downloads anew.
            throw new VoiceNoteUnavailableException(e.getMessage(), e.isTransient(), e);
        }
    }
}
