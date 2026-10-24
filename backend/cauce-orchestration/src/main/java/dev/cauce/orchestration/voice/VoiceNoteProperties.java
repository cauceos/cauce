package dev.cauce.orchestration.voice;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * The limits of a voice note (ADR 0012, V5), bound from {@code cauce.orchestration.voice-notes.*}:
 * longer or larger, it is not transcribed and the person is asked to write it or to send a shorter
 * one. 20 MB is what a Telegram bot may download.
 */
@ConfigurationProperties(prefix = "cauce.orchestration.voice-notes")
public class VoiceNoteProperties {

    private Duration maxDuration = Duration.ofMinutes(2);

    private DataSize maxSize = DataSize.ofMegabytes(20);

    public Duration getMaxDuration() {
        return maxDuration;
    }

    public void setMaxDuration(Duration maxDuration) {
        if (maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("cauce.orchestration.voice-notes.max-duration must be positive");
        }
        this.maxDuration = maxDuration;
    }

    public DataSize getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(DataSize maxSize) {
        if (maxSize == null || maxSize.toBytes() <= 0) {
            throw new IllegalArgumentException("cauce.orchestration.voice-notes.max-size must be positive");
        }
        this.maxSize = maxSize;
    }
}
