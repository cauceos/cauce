package dev.cauce.channels;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelMediaException;
import dev.cauce.channels.spi.MediaChannelAdapter;
import dev.cauce.core.identity.Identity;
import dev.cauce.core.message.MessagePart;
import dev.cauce.orchestration.LogSafe;
import dev.cauce.orchestration.exception.TranscriptionNotAvailableException;
import dev.cauce.orchestration.exception.VoiceNoteUnavailableException;
import dev.cauce.orchestration.voice.VoiceNoteSource;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * The channels' side of {@link VoiceNoteSource} (ADR 0012, V1): resolves the conversation's channel
 * config under the invocation's tenant context (RLS) and asks its channel's
 * {@link MediaChannelAdapter} for the note's bytes. Present whenever the channels module is, so a
 * voice note is read only where a channel can hand it over.
 *
 * <p>While a note is heard, the adapter's typing indicator is repeated every
 * {@link #TYPING_REFRESH} on a thread of its own — it lasts about five seconds on Telegram — until the
 * worker closes the activity. The config and the interlocutor are read first, on the worker's thread
 * and under its tenant context; the repeated call needs neither.
 */
@Component
public class ChannelVoiceNoteSource implements VoiceNoteSource, DisposableBean {

    static final Duration TYPING_REFRESH = Duration.ofSeconds(4);

    private static final Logger log = LoggerFactory.getLogger(ChannelVoiceNoteSource.class);

    private final ChannelConfigService channelConfigService;
    private final InterlocutorLookup interlocutorLookup;
    private final List<MediaChannelAdapter> adapters;
    private final ScheduledExecutorService typing = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "channel-typing");
        thread.setDaemon(true);
        return thread;
    });

    public ChannelVoiceNoteSource(ChannelConfigService channelConfigService, InterlocutorLookup interlocutorLookup,
                                  List<MediaChannelAdapter> adapters) {
        this.channelConfigService = channelConfigService;
        this.interlocutorLookup = interlocutorLookup;
        this.adapters = List.copyOf(adapters);
    }

    @Override
    public byte[] fetch(UUID channelConfigId, MessagePart.Audio note, long maxBytes) {
        ChannelConfig config = channelConfigService.findActiveConfig(channelConfigId).orElseThrow(() ->
                new TranscriptionNotAvailableException("Channel config " + channelConfigId
                        + " is not ACTIVE: the voice note cannot be fetched"));
        MediaChannelAdapter adapter = adapterOf(config).orElseThrow(() -> new TranscriptionNotAvailableException(
                "Channel '" + config.channelType() + "' does not hand over voice notes"));
        try {
            return adapter.download(note.providerReference(), config, maxBytes);
        } catch (ChannelMediaException e) {
            // The worker retries a transient failure with backoff; each attempt downloads anew.
            throw new VoiceNoteUnavailableException(e.getMessage(), e.isTransient(), e);
        }
    }

    @Override
    public Activity showTranscribing(UUID channelConfigId, UUID identityId) {
        try {
            Optional<ChannelConfig> config = channelConfigService.findActiveConfig(channelConfigId);
            Optional<MediaChannelAdapter> adapter = config.flatMap(this::adapterOf);
            Optional<Identity> interlocutor = interlocutorLookup.find(identityId);
            if (adapter.isEmpty() || interlocutor.isEmpty()) {
                return Activity.NONE;
            }
            ScheduledFuture<?> repeated = typing.scheduleAtFixedRate(() -> show(adapter.get(), config.get(),
                    interlocutor.get()), 0, TYPING_REFRESH.toMillis(), TimeUnit.MILLISECONDS);
            return () -> repeated.cancel(false);
        } catch (RuntimeException e) {
            log.debug("Could not show the typing indicator through config {}: {}", channelConfigId,
                    LogSafe.describe(e));
            return Activity.NONE;
        }
    }

    private static void show(MediaChannelAdapter adapter, ChannelConfig config, Identity interlocutor) {
        try {
            adapter.showTyping(config, interlocutor.kind(), interlocutor.value());
        } catch (RuntimeException e) {
            // Caught, or the repetition would stop for good; the note is heard all the same.
            log.debug("Typing indicator refused through config {}: {}", config.id(), LogSafe.describe(e));
        }
    }

    private Optional<MediaChannelAdapter> adapterOf(ChannelConfig config) {
        return adapters.stream().filter(candidate -> candidate.channelType().equals(config.channelType()))
                .findFirst();
    }

    @Override
    public void destroy() {
        typing.shutdownNow();
    }
}
