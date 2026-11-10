package dev.cauce.channels.webchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WebChatLimitsTest {

    @Test
    void aChannelsLimit_mayBeLowerThanThePlatforms_neverHigherNorZero() {
        WebChatLimits own = new WebChatLimits(null, 5, null, null, null);

        assertThat(own.validatedAgainst(WebChatLimits.PLATFORM)).isSameAs(own);
        assertThat(own.effective(WebChatLimits.PLATFORM))
                .isEqualTo(new WebChatLimits(10, 5, 100, 30, 60));
        assertThatThrownBy(() -> new WebChatLimits(null, 11, null, null, null).validatedAgainst(WebChatLimits.PLATFORM))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("messages_per_session_minute");
        assertThatThrownBy(() -> new WebChatLimits(0, null, null, null, null).validatedAgainst(WebChatLimits.PLATFORM))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sessions_per_ip_hour");
        assertThat(WebChatLimits.NONE.effective(WebChatLimits.PLATFORM)).isEqualTo(WebChatLimits.PLATFORM);
    }
}
