package dev.cauce.api.webchat;

import static org.assertj.core.api.Assertions.assertThat;

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
}
