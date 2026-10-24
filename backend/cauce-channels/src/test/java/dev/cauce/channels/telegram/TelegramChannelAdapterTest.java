package dev.cauce.channels.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelDeliveryException;
import dev.cauce.channels.spi.ChannelInboundMessage;
import dev.cauce.channels.spi.ChannelMediaException;
import dev.cauce.channels.spi.ChannelOutboundMessage;
import dev.cauce.channels.spi.ChannelPayloadException;
import dev.cauce.channels.spi.UnsupportedInboundMessage;
import dev.cauce.channels.spi.UnsupportedInterlocutorException;
import dev.cauce.channels.spi.WebhookRequest;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.message.MessagePart;
import dev.cauce.orchestration.exception.VoiceNoteRejectedException;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@WireMockTest
class TelegramChannelAdapterTest {

    /** Deterministic stand-in for the HMAC hasher: hash(x) = "hash:" + x. */
    private static final ApiKeyHasher FAKE_HASHER = new ApiKeyHasher() {
        @Override
        public String hash(String plaintext) {
            return "hash:" + plaintext;
        }

        @Override
        public boolean matches(String plaintext, String hash) {
            return hash(plaintext).equals(hash);
        }
    };

    private final TelegramChannelAdapter adapter =
            new TelegramChannelAdapter(FAKE_HASHER, HttpClient.newHttpClient(), new TelegramProperties());

    private final ChannelConfig config = ChannelConfig.create(
            java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "telegram",
            "12345:bot-token", FAKE_HASHER.hash("s3cret"));

    private TelegramChannelAdapter adapterAgainst(WireMockRuntimeInfo wm) {
        TelegramProperties properties = new TelegramProperties();
        properties.setBaseUrl(wm.getHttpBaseUrl());
        return new TelegramChannelAdapter(FAKE_HASHER, HttpClient.newHttpClient(), properties);
    }

    @Test
    void channelType_always_isTelegram() {
        assertThat(adapter.channelType()).isEqualTo("telegram");
    }

    @Test
    void verify_matchingSecretHeader_passes() {
        WebhookRequest request = new WebhookRequest(
                Map.of("X-Telegram-Bot-Api-Secret-Token", "s3cret"), "{}");

        assertThat(adapter.verify(request, config)).isTrue();
    }

    @Test
    void verify_headerLookup_isCaseInsensitive() {
        WebhookRequest request = new WebhookRequest(
                Map.of("x-telegram-bot-api-secret-token", "s3cret"), "{}");

        assertThat(adapter.verify(request, config)).isTrue();
    }

    @Test
    void verify_wrongSecret_fails() {
        WebhookRequest request = new WebhookRequest(
                Map.of("X-Telegram-Bot-Api-Secret-Token", "wrong"), "{}");

        assertThat(adapter.verify(request, config)).isFalse();
    }

    @Test
    void verify_missingSecretHeader_fails() {
        assertThat(adapter.verify(new WebhookRequest(Map.of(), "{}"), config)).isFalse();
    }

    @Test
    void parse_textMessageUpdate_normalizesRefContentAndScopedKey() {
        String update = """
                {"update_id": 736294857,
                 "message": {"message_id": 42,
                             "from": {"id": 111, "first_name": "Ana"},
                             "chat": {"id": 987654321, "type": "private"},
                             "date": 1751623000,
                             "text": "Hola, quiero una cita"}}
                """;

        Optional<ChannelInboundMessage> parsed = adapter.parse(update, config);

        assertThat(parsed).hasValueSatisfying(message -> {
            assertThat(message.identityKind()).isEqualTo(IdentityKind.PROVIDER_USER_ID);
            assertThat(message.identityValue()).isEqualTo("987654321");
            assertThat(message.content()).isEqualTo("Hola, quiero una cita");
            assertThat(message.idempotencyKey()).isEqualTo(config.id() + ":736294857");
        });
    }

    @Test
    void parse_groupChat_failsLoudly_insteadOfMintingAPersonIdentity() {
        String update = """
                {"update_id": 1, "message": {"chat": {"id": -100123456, "type": "supergroup"}, "text": "hi"}}
                """;

        assertThatThrownBy(() -> adapter.parse(update, config))
                .isInstanceOf(UnsupportedInterlocutorException.class)
                .hasMessageContaining("supergroup");
    }

    @Test
    void parse_textMessageWithoutChatType_failsLoudly() {
        String update = """
                {"update_id": 1, "message": {"chat": {"id": 987654321}, "text": "hi"}}
                """;

        assertThatThrownBy(() -> adapter.parse(update, config))
                .isInstanceOf(UnsupportedInterlocutorException.class);
    }

    @Test
    void parse_editedMessageUpdate_isIgnored() {
        String update = """
                {"update_id": 2,
                 "edited_message": {"chat": {"id": 987654321}, "text": "edited"}}
                """;

        assertThat(adapter.parse(update, config)).isEmpty();
    }

    @Test
    void parse_messageWithoutText_isIgnored() {
        String update = """
                {"update_id": 3, "message": {"chat": {"id": 987654321}, "photo": []}}
                """;

        assertThat(adapter.parse(update, config)).isEmpty();
    }

    @Test
    void unsupported_aPrivateVoiceNote_isNamedWithItsSenderTypeAndUpdate_toBeAnswered() {
        String update = """
                {"update_id": 7007, "message": {"chat": {"id": 987654321, "type": "private"},
                 "voice": {"file_id": "AwAC", "duration": 4, "mime_type": "audio/ogg"}}}
                """;

        Optional<UnsupportedInboundMessage> unsupported = adapter.unsupported(update, config);

        assertThat(unsupported).contains(new UnsupportedInboundMessage(IdentityKind.PROVIDER_USER_ID, "987654321",
                "voice", "7007", new TelegramProperties().getUnsupportedMessageReply()));
    }

    @Test
    void unsupported_aPrivatePhoto_isNamedPhoto_andAnUnknownKind_other() {
        assertThat(adapter.unsupported("""
                {"update_id": 8, "message": {"chat": {"id": 1, "type": "private"}, "photo": []}}
                """, config)).map(UnsupportedInboundMessage::messageType).contains("photo");
        assertThat(adapter.unsupported("""
                {"update_id": 9, "message": {"chat": {"id": 1, "type": "private"}, "paid_media": {}}}
                """, config)).map(UnsupportedInboundMessage::messageType).contains("other");
    }

    @Test
    void unsupported_isEmpty_forText_aGroup_anEdit_orMalformedJson() {
        assertThat(adapter.unsupported("""
                {"update_id": 1, "message": {"chat": {"id": 1, "type": "private"}, "text": "Hola"}}
                """, config)).isEmpty();
        assertThat(adapter.unsupported("""
                {"update_id": 2, "message": {"chat": {"id": -100, "type": "group"}, "voice": {}}}
                """, config)).isEmpty();
        assertThat(adapter.unsupported("""
                {"update_id": 3, "edited_message": {"chat": {"id": 1, "type": "private"}, "voice": {}}}
                """, config)).isEmpty();
        assertThat(adapter.unsupported("{not json", config)).isEmpty();
    }

    @Test
    void parse_aPrivateVoiceNote_isItsAudioPart_byFileIdSizeAndLength_withItsCaptionAfter() {
        Optional<ChannelInboundMessage> parsed = adapter.parse("""
                {"update_id": 7007, "message": {"chat": {"id": 987654321, "type": "private"},
                 "voice": {"file_id": "AwACAgQ", "file_unique_id": "u", "duration": 4, "mime_type": "audio/ogg",
                           "file_size": 6200},
                 "caption": "para el sábado"}}
                """, config);

        assertThat(parsed).hasValueSatisfying(message -> {
            assertThat(message.identityValue()).isEqualTo("987654321");
            assertThat(message.idempotencyKey()).isEqualTo(config.id() + ":7007");
            assertThat(message.parts()).containsExactly(
                    MessagePart.Audio.received("audio/ogg", "AwACAgQ", 6200L, 4), new MessagePart.Text("para el sábado"));
            assertThat(message.hasAudio()).isTrue();
        });
    }

    @Test
    void parse_aVoiceNoteFromAGroup_failsLoudly_asAnyMessageFromAGroup() {
        assertThatThrownBy(() -> adapter.parse("""
                {"update_id": 7008, "message": {"chat": {"id": -100, "type": "group"}, "voice": {"file_id": "x"}}}
                """, config)).isInstanceOf(UnsupportedInterlocutorException.class);
    }

    @Test
    void download_findsTheFileWithGetFile_andReadsItWithTheBotToken(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/bot12345:bot-token/getFile?file_id=AwACAgQ")).willReturn(okJson("""
                {"ok": true, "result": {"file_id": "AwACAgQ", "file_size": 11, "file_path": "voice/file_7.oga"}}
                """)));
        stubFor(get(urlEqualTo("/file/bot12345:bot-token/voice/file_7.oga"))
                .willReturn(aResponse().withStatus(200).withBody("OggS-opus!!")));

        byte[] bytes = adapterAgainst(wm).download("AwACAgQ", config, 20_000_000);

        assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("OggS-opus!!");
    }

    @Test
    void download_aFileOverTheLimit_isRejected_beforeItIsRead(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/bot12345:bot-token/getFile?file_id=big")).willReturn(okJson("""
                {"ok": true, "result": {"file_id": "big", "file_size": 30000000, "file_path": "voice/big.oga"}}
                """)));

        assertThatThrownBy(() -> adapterAgainst(wm).download("big", config, 20_000_000))
                .isInstanceOf(VoiceNoteRejectedException.class);
        verify(0, getRequestedFor(urlEqualTo("/file/bot12345:bot-token/voice/big.oga")));
    }

    @Test
    void download_aReferenceTelegramDoesNotKnow_isAMediaFailure_neverShowingTheToken(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/bot12345:bot-token/getFile?file_id=gone")).willReturn(aResponse().withStatus(400)
                .withBody("{\"ok\": false, \"description\": \"Bad Request: invalid file_id\"}")));

        assertThatThrownBy(() -> adapterAgainst(wm).download("gone", config, 20_000_000))
                .isInstanceOf(ChannelMediaException.class)
                .hasMessageContaining("invalid file_id")
                .hasMessageNotContaining("bot-token")
                .satisfies(e -> assertThat(((ChannelMediaException) e).isTransient()).isFalse());
    }

    @Test
    void download_aTelegramOutage_isATransientFailure_thatALaterAttemptMayGetPast(WireMockRuntimeInfo wm) {
        stubFor(get(urlEqualTo("/bot12345:bot-token/getFile?file_id=AwACAgQ")).willReturn(aResponse().withStatus(502)));

        assertThatThrownBy(() -> adapterAgainst(wm).download("AwACAgQ", config, 20_000_000))
                .isInstanceOf(ChannelMediaException.class)
                .satisfies(e -> assertThat(((ChannelMediaException) e).isTransient()).isTrue());
    }

    @Test
    void parse_malformedJson_throwsChannelPayloadException() {
        assertThatThrownBy(() -> adapter.parse("{not json", config))
                .isInstanceOf(ChannelPayloadException.class)
                .hasMessageContaining("Malformed");
    }

    @Test
    void parse_missingUpdateId_throwsChannelPayloadException() {
        assertThatThrownBy(() -> adapter.parse(
                "{\"message\": {\"chat\": {\"id\": 1}, \"text\": \"hi\"}}", config))
                .isInstanceOf(ChannelPayloadException.class)
                .hasMessageContaining("update_id");
    }

    // === OUTBOUND (deliver) ===

    @Test
    void deliver_textReply_postsSendMessageWithChatIdAndToken(WireMockRuntimeInfo wm) {
        stubFor(post("/bot12345:bot-token/sendMessage").willReturn(okJson("{\"ok\": true}")));

        adapterAgainst(wm).deliver(
                new ChannelOutboundMessage(IdentityKind.PROVIDER_USER_ID, "987654321", "Hola, soy un agente"), config);

        verify(postRequestedFor(urlEqualTo("/bot12345:bot-token/sendMessage"))
                .withRequestBody(equalToJson(
                        "{\"chat_id\": \"987654321\", \"text\": \"Hola, soy un agente\", \"parse_mode\": \"HTML\"}")));
    }

    @Test
    void showTyping_postsSendChatActionTyping_toTheChat(WireMockRuntimeInfo wm) {
        stubFor(post("/bot12345:bot-token/sendChatAction").willReturn(okJson("{\"ok\": true, \"result\": true}")));

        adapterAgainst(wm).showTyping(config, IdentityKind.PROVIDER_USER_ID, "987654321");

        verify(postRequestedFor(urlEqualTo("/bot12345:bot-token/sendChatAction"))
                .withRequestBody(equalToJson("{\"chat_id\": \"987654321\", \"action\": \"typing\"}")));
    }

    @Test
    void showTyping_refusedByTelegram_failsLoudlyForTheCallerToLog(WireMockRuntimeInfo wm) {
        stubFor(post("/bot12345:bot-token/sendChatAction").willReturn(aResponse().withStatus(403)
                .withBody("{\"ok\": false, \"description\": \"Forbidden: bot was blocked by the user\"}")));

        assertThatThrownBy(() -> adapterAgainst(wm).showTyping(config, IdentityKind.PROVIDER_USER_ID, "987654321"))
                .isInstanceOf(ChannelDeliveryException.class)
                .hasMessageContaining("sendChatAction returned HTTP 403")
                .hasMessageNotContaining("bot-token");
    }

    @Test
    void deliver_rendersTheAgentsMarkdownAsTelegramHtml_neverLiteralAsterisks(WireMockRuntimeInfo wm) {
        stubFor(post("/bot12345:bot-token/sendMessage").willReturn(okJson("{\"ok\": true}")));

        adapterAgainst(wm).deliver(new ChannelOutboundMessage(IdentityKind.PROVIDER_USER_ID, "987654321",
                "Tu código es **9PNVXQ**. 2 < 3 & más"), config);

        verify(postRequestedFor(urlEqualTo("/bot12345:bot-token/sendMessage")).withRequestBody(equalToJson(
                "{\"chat_id\": \"987654321\", \"text\": \"Tu código es <b>9PNVXQ</b>. 2 &lt; 3 &amp; más\","
                        + " \"parse_mode\": \"HTML\"}")));
    }

    @Test
    void deliver_whenTelegramRejectsTheEntities_sendsTheSameReplyOnceAsPlainText(WireMockRuntimeInfo wm) {
        stubFor(post("/bot12345:bot-token/sendMessage").withRequestBody(containing("parse_mode"))
                .willReturn(aResponse().withStatus(400).withBody(
                        "{\"ok\": false, \"description\": \"Bad Request: can't parse entities: unclosed tag\"}")));
        stubFor(post("/bot12345:bot-token/sendMessage").withRequestBody(notContaining("parse_mode"))
                .willReturn(okJson("{\"ok\": true}")));

        adapterAgainst(wm).deliver(new ChannelOutboundMessage(IdentityKind.PROVIDER_USER_ID, "987654321",
                "Tu código es **9PNVXQ**"), config);

        verify(2, postRequestedFor(urlEqualTo("/bot12345:bot-token/sendMessage")));
        verify(postRequestedFor(urlEqualTo("/bot12345:bot-token/sendMessage")).withRequestBody(equalToJson(
                "{\"chat_id\": \"987654321\", \"text\": \"Tu código es 9PNVXQ\"}")));
    }

    @Test
    void deliver_toAKindTelegramDidNotProduce_failsWithoutCallingTelegram(WireMockRuntimeInfo wm) {
        assertThatThrownBy(() -> adapterAgainst(wm).deliver(
                new ChannelOutboundMessage(IdentityKind.PHONE_NUMBER, "+34600111222", "Hola"), config))
                .isInstanceOf(ChannelDeliveryException.class)
                .hasMessageContaining("PHONE_NUMBER");
        verify(0, postRequestedFor(urlEqualTo("/bot12345:bot-token/sendMessage")));
    }

    @Test
    void deliver_non2xx_throwsChannelDeliveryException(WireMockRuntimeInfo wm) {
        stubFor(post("/bot12345:bot-token/sendMessage").willReturn(serverError()));

        assertThatThrownBy(() -> adapterAgainst(wm).deliver(
                new ChannelOutboundMessage(IdentityKind.PROVIDER_USER_ID, "987654321", "Hola"), config))
                .isInstanceOf(ChannelDeliveryException.class)
                .hasMessageContaining("500");
    }

    @Test
    void deliver_okFalse_throwsChannelDeliveryException(WireMockRuntimeInfo wm) {
        stubFor(post("/bot12345:bot-token/sendMessage").willReturn(okJson(
                "{\"ok\": false, \"description\": \"Bad Request: chat not found\"}")));

        assertThatThrownBy(() -> adapterAgainst(wm).deliver(
                new ChannelOutboundMessage(IdentityKind.PROVIDER_USER_ID, "987654321", "Hola"), config))
                .isInstanceOf(ChannelDeliveryException.class)
                .hasMessageContaining("chat not found");
    }
}
