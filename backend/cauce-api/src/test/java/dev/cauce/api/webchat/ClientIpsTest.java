package dev.cauce.api.webchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cauce.channels.webchat.WebChatProperties;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpsTest {

    private static MockHttpServletRequest from(String remote, String cfConnectingIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        if (cfConnectingIp != null) {
            request.addHeader("CF-Connecting-IP", cfConnectingIp);
        }
        return request;
    }

    @Test
    void cfConnectingIp_isTheVisitorOnlyThroughATrustedProxy_andOnlyAnHmacComesOut() {
        WebChatProperties properties = new WebChatProperties();
        properties.setIpHashKey(Base64.getEncoder().encodeToString(new byte[32]));
        properties.setTrustedProxies(List.of("10.0.0.2"));
        ClientIps ips = new ClientIps(properties);

        String viaProxy = ips.hashOf(from("10.0.0.2", "203.0.113.7"));
        String direct = ips.hashOf(from("203.0.113.7", null));
        String spoofed = ips.hashOf(from("198.51.100.9", "203.0.113.7"));

        assertThat(viaProxy).isEqualTo(direct).hasSize(64).doesNotContain("203.0.113.7");
        assertThat(spoofed).isEqualTo(ips.hashOf(from("198.51.100.9", null))).isNotEqualTo(direct);
    }

    @Test
    void constructor_withoutAKey_orWithOneThatIsNot32Bytes_refusesToStart_neverFallsBack() {
        for (String key : new String[] {null, " ", "not base64!", Base64.getEncoder().encodeToString(new byte[16])}) {
            WebChatProperties properties = new WebChatProperties();
            properties.setIpHashKey(key);

            assertThatThrownBy(() -> new ClientIps(properties)).as("key %s", key)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("cauce.web.ip-hash-key");
        }
    }

    @Test
    void theDefaultKey_isTheDevOnlyOne_andWorks() {
        assertThat(new WebChatProperties().getIpHashKey()).isEqualTo(WebChatProperties.DEV_ONLY_IP_HASH_KEY);
        assertThat(Base64.getDecoder().decode(WebChatProperties.DEV_ONLY_IP_HASH_KEY)).hasSize(32);
        assertThat(new ClientIps(new WebChatProperties()).hashOf(from("203.0.113.7", null))).hasSize(64);
    }
}
