package dev.cauce.channels.webchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebChatSettingsTest {

    private static final UUID CONFIG = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();

    private static WebChatSettings settings(List<String> origins, String privacy) {
        return new WebChatSettings(CONFIG, TENANT, origins, "site-key", privacy, ConsentMode.NOTICE);
    }

    @Test
    void anOrigin_isKeptExact_lowercase_withoutTrailingSlash_andComparedIgnoringCase() {
        WebChatSettings s = settings(List.of("https://Tienda.Example.com/", "https://tienda.example.com:8443",
                "http://localhost:4321", "https://tienda.example.com"), "https://tienda.example.com/privacidad");

        assertThat(s.allowedOrigins()).containsExactly("https://tienda.example.com", "https://tienda.example.com:8443",
                "http://localhost:4321");
        assertThat(s.allows("https://TIENDA.example.com")).isTrue();
        assertThat(s.allows("https://otra.example.com")).isFalse();
        assertThat(s.allows("https://tienda.example.com:8080")).isFalse();
        assertThat(s.allows(null)).isFalse();
    }

    @Test
    void anOriginWithAPath_aQuery_credentials_orPlainHttpOutsideTheLocalMachine_isRefused() {
        for (String bad : List.of("https://tienda.example.com/tienda", "https://tienda.example.com?x=1",
                "https://user@tienda.example.com", "http://tienda.example.com", "tienda.example.com", "*.example.com")) {
            assertThatThrownBy(() -> settings(List.of(bad), "https://tienda.example.com/p"))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> settings(List.of(), "https://tienda.example.com/p"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one");
    }

    @Test
    void thePrivacyPolicy_isAnHttpsUrl_andTheSiteKeyIsRequired() {
        assertThatThrownBy(() -> settings(List.of("https://tienda.example.com"), "http://tienda.example.com/p"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("https");
        assertThatThrownBy(() -> new WebChatSettings(CONFIG, TENANT, List.of("https://tienda.example.com"), " ",
                "https://tienda.example.com/p", ConsentMode.NOTICE)).isInstanceOf(IllegalArgumentException.class);
    }
}
