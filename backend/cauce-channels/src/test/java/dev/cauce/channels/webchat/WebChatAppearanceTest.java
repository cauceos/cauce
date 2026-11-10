package dev.cauce.channels.webchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WebChatAppearanceTest {

    private static WebChatAppearance with(String primary, String onPrimary, String background, String text) {
        return new WebChatAppearance("Cauce", null, primary, onPrimary, background, text, "Inter, sans-serif",
                "¿En qué te ayudo?", null, null, "Ahora no puedo responder.", "hola@caucewebs.es", "es");
    }

    @Test
    void aLookWhosePairsReachAa_isKept_normalised() {
        WebChatAppearance ok = with("#0B5FFF", "#FFFFFF", "#FFFFFF", "#111827");

        assertThat(ok.primaryColor()).isEqualTo("#0b5fff");
        assertThat(WebChatAppearance.contrast("#000000", "#ffffff")).isEqualTo(21.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(WebChatAppearance.NEUTRAL.locale()).isEqualTo("es");
    }

    @Test
    void aPairBelowAa_aBadColour_aBadFont_orAPlainHttpAvatar_isRefused() {
        assertThatThrownBy(() -> with("#0b5fff", "#ffffff", "#ffffff", "#9ca3af"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("text_color on background_color");
        assertThatThrownBy(() -> with("#ffeb3b", "#ffffff", "#ffffff", "#111827"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("on_primary_color");
        assertThatThrownBy(() -> with("blue", "#ffffff", "#ffffff", "#111827"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("#RRGGBB");
        assertThatThrownBy(() -> new WebChatAppearance("C", null, "#0b5fff", "#ffffff", "#ffffff", "#111827",
                "x; background: url(evil)", null, null, null, null, null, "es"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("font_family");
        assertThatThrownBy(() -> new WebChatAppearance("C", "http://x.example/a.png", "#0b5fff", "#ffffff", "#ffffff",
                "#111827", null, null, null, null, null, null, "es"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("https");
    }
}
