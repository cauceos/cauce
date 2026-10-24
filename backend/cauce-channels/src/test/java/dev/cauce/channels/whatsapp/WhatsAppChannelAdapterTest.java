package dev.cauce.channels.whatsapp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelDeliveryException;
import dev.cauce.channels.spi.OutsideReplyWindowException;
import dev.cauce.channels.spi.ChannelOutboundMessage;
import dev.cauce.core.identity.IdentityKind;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@WireMockTest
class WhatsAppChannelAdapterTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final String BSUID = "ES.13491208655302741918";
    private static final String SEND_PATH = "/v24.0/1111111111/messages";

    private final ChannelConfig number = ChannelConfig.createWhatsAppNumber(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "1111111111", "EAAG-access-token");

    private WhatsAppChannelAdapter adapterAgainst(WireMockRuntimeInfo wm) {
        WhatsAppProperties properties = new WhatsAppProperties();
        properties.setBaseUrl(wm.getHttpBaseUrl());
        return new WhatsAppChannelAdapter(HttpClient.newHttpClient(), properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ChannelOutboundMessage reply(String text, Duration sinceInterlocutor) {
        return new ChannelOutboundMessage(IdentityKind.PROVIDER_USER_ID, BSUID, text, NOW.minus(sinceInterlocutor));
    }

    @Test
    void download_asksGraphForTheMediaUrl_andReadsItWithTheNumbersToken(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/v24.0/media-9")).withHeader("Authorization", equalTo("Bearer EAAG-access-token"))
                .willReturn(okJson("""
                        {"url": "%s/lookaside/media-9", "mime_type": "audio/ogg", "file_size": 9, "id": "media-9"}
                        """.formatted(wm.getHttpBaseUrl()))));
        stubFor(get(urlEqualTo("/lookaside/media-9")).withHeader("Authorization", equalTo("Bearer EAAG-access-token"))
                .willReturn(aResponse().withStatus(200).withBody("OggS-wa!!")));

        byte[] bytes = adapterAgainst(wm).download("media-9", number, 20_000_000);

        assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("OggS-wa!!");
    }

    @Test
    void download_eachAttemptAsksForANewUrl_soARetryGetsPastAnExpiredOrFailedOne(WireMockRuntimeInfo wm) {
        WhatsAppChannelAdapter adapter = adapterAgainst(wm);
        stubFor(get(urlEqualTo("/v24.0/media-9")).inScenario("urls").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"url\": \"%s/lookaside/first\", \"id\": \"media-9\"}"
                        .formatted(wm.getHttpBaseUrl()))).willSetStateTo("second"));
        stubFor(get(urlEqualTo("/v24.0/media-9")).inScenario("urls").whenScenarioStateIs("second")
                .willReturn(okJson("{\"url\": \"%s/lookaside/second\", \"id\": \"media-9\"}"
                        .formatted(wm.getHttpBaseUrl()))));
        stubFor(get(urlEqualTo("/lookaside/first")).willReturn(aResponse().withStatus(503)));
        stubFor(get(urlEqualTo("/lookaside/second")).willReturn(aResponse().withStatus(200).withBody("OggS-wa!!")));

        assertThatThrownBy(() -> adapter.download("media-9", number, 20_000_000))
                .isInstanceOf(dev.cauce.channels.spi.ChannelMediaException.class)
                .satisfies(e -> assertThat(((dev.cauce.channels.spi.ChannelMediaException) e).isTransient()).isTrue());
        byte[] retried = adapter.download("media-9", number, 20_000_000);

        assertThat(new String(retried, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("OggS-wa!!");
        verify(2, getRequestedFor(urlEqualTo("/v24.0/media-9")));
    }

    @Test
    void download_aMediaOverTheLimit_isRejected_beforeItIsRead(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/v24.0/media-big")).willReturn(okJson("""
                {"url": "%s/lookaside/big", "file_size": 30000000, "id": "media-big"}
                """.formatted(wm.getHttpBaseUrl()))));

        assertThatThrownBy(() -> adapterAgainst(wm).download("media-big", number, 20_000_000))
                .isInstanceOf(dev.cauce.orchestration.exception.VoiceNoteRejectedException.class);
        verify(0, getRequestedFor(urlEqualTo("/lookaside/big")));
    }

    @Test
    void download_aMediaMetaDoesNotServe_isAMediaFailure(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/v24.0/media-gone")).willReturn(aResponse().withStatus(404)
                .withBody("{\"error\": {\"code\": 100, \"message\": \"Unsupported get request\"}}")));

        assertThatThrownBy(() -> adapterAgainst(wm).download("media-gone", number, 20_000_000))
                .isInstanceOf(dev.cauce.channels.spi.ChannelMediaException.class)
                .hasMessageContaining("404")
                .hasMessageNotContaining("EAAG-access-token");
    }

    @Test
    void channelType_isWhatsapp() {
        assertThat(new WhatsAppChannelAdapter(HttpClient.newHttpClient(), new WhatsAppProperties(), Clock.systemUTC())
                .channelType()).isEqualTo("whatsapp");
    }

    @Test
    void deliver_postsATextMessageToTheBsuid_withTheNumbersToken_neverATo_inWhatsAppsMarkers(
            WireMockRuntimeInfo wm) {
        stubFor(post(SEND_PATH).willReturn(okJson("{\"messages\":[{\"id\":\"wamid.X\"}]}")));

        adapterAgainst(wm).deliver(reply("Hola, ¿en qué te **ayudo**?", Duration.ofMinutes(1)), number);

        verify(postRequestedFor(urlEqualTo(SEND_PATH))
                .withHeader("Authorization", equalTo("Bearer EAAG-access-token"))
                .withRequestBody(equalToJson("""
                        {"messaging_product":"whatsapp","recipient_type":"individual",
                         "recipient":"ES.13491208655302741918","type":"text",
                         "text":{"body":"Hola, ¿en qué te *ayudo*?","preview_url":false}}""")));
    }

    @Test
    void deliver_outsideThe24HourWindow_failsLoudly_withoutCallingMeta(WireMockRuntimeInfo wm) {
        assertThatThrownBy(() -> adapterAgainst(wm).deliver(reply("Hola", Duration.ofHours(25)), number))
                .isInstanceOf(OutsideReplyWindowException.class)
                .hasMessageContaining("24-hour");
        verify(0, postRequestedFor(urlEqualTo(SEND_PATH)));
    }

    @Test
    void deliver_withoutKnowingWhenTheInterlocutorWrote_failsLoudly(WireMockRuntimeInfo wm) {
        ChannelOutboundMessage unknown = new ChannelOutboundMessage(IdentityKind.PROVIDER_USER_ID, BSUID, "Hola");

        assertThatThrownBy(() -> adapterAgainst(wm).deliver(unknown, number))
                .isInstanceOf(ChannelDeliveryException.class);
        verify(0, postRequestedFor(urlEqualTo(SEND_PATH)));
    }

    @Test
    void deliver_aTextOverTheLimit_isNotSplit_andFailsLoudly(WireMockRuntimeInfo wm) {
        assertThatThrownBy(() -> adapterAgainst(wm).deliver(reply("x".repeat(4097), Duration.ofMinutes(1)), number))
                .isInstanceOf(ChannelDeliveryException.class)
                .hasMessageContaining("4096");
        verify(0, postRequestedFor(urlEqualTo(SEND_PATH)));
    }

    @Test
    void deliver_toAKindOtherThanABsuid_isRefused(WireMockRuntimeInfo wm) {
        ChannelOutboundMessage phone = new ChannelOutboundMessage(IdentityKind.PHONE_NUMBER, "+34600111222", "Hola",
                NOW);

        assertThatThrownBy(() -> adapterAgainst(wm).deliver(phone, number))
                .isInstanceOf(ChannelDeliveryException.class)
                .hasMessageContaining("PHONE_NUMBER");
    }

    @Test
    void deliver_metaError_surfacesItsCodeAndMessage_neverTheToken(WireMockRuntimeInfo wm) {
        stubFor(post(SEND_PATH).willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"Re-engagement message\",\"code\":131047}}")));

        assertThatThrownBy(() -> adapterAgainst(wm).deliver(reply("Hola", Duration.ofMinutes(1)), number))
                .isInstanceOf(OutsideReplyWindowException.class)
                .hasMessageContaining("131047")
                .hasMessageContaining("24-hour")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("EAAG-access-token"));
    }
}
