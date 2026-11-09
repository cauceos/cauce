package dev.cauce.channels.webchat;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

@WireMockTest
class CloudflareTurnstileVerifierTest {

    private static CloudflareTurnstileVerifier against(String url) {
        WebChatProperties properties = new WebChatProperties();
        properties.setTurnstileVerifyUrl(url);
        properties.setTurnstileTimeout(Duration.ofSeconds(2));
        return new CloudflareTurnstileVerifier(HttpClient.newHttpClient(), properties);
    }

    @Test
    void aValidToken_verifies_andTheRequestCarriesTheSecretAndTheTokenButNeverAnIp(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/siteverify")).willReturn(okJson("""
                {"success": true, "challenge_ts": "2026-11-09T16:00:00Z", "hostname": "tienda.example.com"}
                """)));

        assertThat(against(wm.getHttpBaseUrl() + "/siteverify").verify("el-secreto", "el-token")).isTrue();

        verify(postRequestedFor(urlEqualTo("/siteverify"))
                .withRequestBody(containing("secret=el-secreto"))
                .withRequestBody(containing("response=el-token"))
                .withRequestBody(notContaining("remoteip")));
    }

    @Test
    void aRejectedToken_aMalformedAnswer_orAnEmptyToken_doesNotVerify(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/siteverify")).willReturn(okJson("""
                {"success": false, "error-codes": ["invalid-input-response"]}
                """)));
        stubFor(post(urlEqualTo("/garbage")).willReturn(okJson("not json")));

        assertThat(against(wm.getHttpBaseUrl() + "/siteverify").verify("s", "t")).isFalse();
        assertThat(against(wm.getHttpBaseUrl() + "/garbage").verify("s", "t")).isFalse();
        assertThat(against(wm.getHttpBaseUrl() + "/siteverify").verify("s", " ")).isFalse();
    }

    @Test
    void turnstileDownOrUnreachable_isUnavailable_neverAVerifiedSession(WireMockRuntimeInfo wm) {
        stubFor(post(urlEqualTo("/siteverify")).willReturn(serverError()));

        assertThatThrownBy(() -> against(wm.getHttpBaseUrl() + "/siteverify").verify("s", "t"))
                .isInstanceOf(TurnstileUnavailableException.class);
        assertThatThrownBy(() -> against("http://127.0.0.1:1/siteverify").verify("s", "t"))
                .isInstanceOf(TurnstileUnavailableException.class);
    }
}
