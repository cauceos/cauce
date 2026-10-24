package dev.cauce.channels.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.channels.UnsupportedMessageRecorder;
import dev.cauce.channels.WebhookAuthenticationException;
import dev.cauce.channels.InboundInterception;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelDeliveryException;
import dev.cauce.channels.spi.ChannelOutboundMessage;
import dev.cauce.channels.spi.ChannelPayloadException;
import dev.cauce.channels.spi.WebhookRequest;
import dev.cauce.channels.whatsapp.app.WhatsAppApp;
import dev.cauce.channels.whatsapp.app.WhatsAppAppNotFoundException;
import dev.cauce.channels.whatsapp.app.WhatsAppAppService;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.message.MessagePart;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.InboundMessageService;
import dev.cauce.orchestration.voice.VoiceNoteTranscription;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;

class WhatsAppWebhookServiceTest {

    private static final String REPLY_TEXT = new WhatsAppProperties().getUnsupportedMessageReply();

    private static final String SECRET = "app-secret";
    private static final String BSUID = "ES.13491208655302741918";

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

    private WhatsAppAppService appService;
    private WhatsAppNumberService numberService;
    private InboundMessageService inbound;
    private WhatsAppChannelAdapter outbound;
    private UnsupportedMessageRecorder recorder;
    private VoiceNoteTranscription voiceNotes;
    private WhatsAppWebhookService service;
    private WhatsAppApp app;
    private ChannelConfig number;

    @BeforeEach
    void setUp() {
        appService = mock(WhatsAppAppService.class);
        numberService = mock(WhatsAppNumberService.class);
        inbound = mock(InboundMessageService.class);
        outbound = mock(WhatsAppChannelAdapter.class);
        recorder = mock(UnsupportedMessageRecorder.class);
        voiceNotes = mock(VoiceNoteTranscription.class);
        service = new WhatsAppWebhookService(appService, numberService, inbound, outbound, recorder,
                new WhatsAppProperties(), FAKE_HASHER, new SyncTaskExecutor(), mock(InboundInterception.class),
                Clock.systemUTC(), voiceNotes);
        app = WhatsAppApp.create(UUID.randomUUID(), "1234567890", SECRET, FAKE_HASHER.hash("verify-me"));
        number = ChannelConfig.createWhatsAppNumber(UUID.randomUUID(), UUID.randomUUID(), app.id(), "1111111111",
                "EAAG-token");
        when(appService.resolveActiveApp(app.id())).thenReturn(Optional.of(app));
        when(numberService.resolveNumber(app.id(), "1111111111")).thenReturn(Optional.of(number));
    }

    // --- subscription GET ---

    @Test
    void verifySubscription_withTheAppsToken_echoesTheChallenge() {
        assertThat(service.verifySubscription(app.id(), "subscribe", "verify-me", "1158201444")).isEqualTo("1158201444");
    }

    @Test
    void verifySubscription_withAWrongTokenOrMode_isRejected() {
        assertThatThrownBy(() -> service.verifySubscription(app.id(), "subscribe", "wrong", "c"))
                .isInstanceOf(WebhookAuthenticationException.class);
        assertThatThrownBy(() -> service.verifySubscription(app.id(), "unsubscribe", "verify-me", "c"))
                .isInstanceOf(WebhookAuthenticationException.class);
        assertThatThrownBy(() -> service.verifySubscription(UUID.randomUUID(), "subscribe", "verify-me", "c"))
                .isInstanceOf(WhatsAppAppNotFoundException.class);
    }

    // --- signed POST ---

    @Test
    void handle_anUnsignedOrWronglySignedRequest_isRejected_beforeTheBodyIsReadOrRouted() {
        byte[] notEvenJson = "{not json".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.handle(app.id(), new WebhookRequest(Map.of(), notEvenJson)))
                .isInstanceOf(WebhookAuthenticationException.class);
        assertThatThrownBy(() -> service.handle(app.id(), new WebhookRequest(
                Map.of("X-Hub-Signature-256", WhatsAppSignature.sign(notEvenJson, "other-secret")), notEvenJson)))
                .isInstanceOf(WebhookAuthenticationException.class);
        verifyNoInteractions(numberService, inbound, outbound, recorder);
    }

    @Test
    void handle_anUnknownApp_isNotFound() {
        assertThatThrownBy(() -> service.handle(UUID.randomUUID(), signed("{}")))
                .isInstanceOf(WhatsAppAppNotFoundException.class);
    }

    @Test
    void handle_aSignedMalformedBody_isAPayloadError() {
        assertThatThrownBy(() -> service.handle(app.id(), signed("{not json")))
                .isInstanceOf(ChannelPayloadException.class);
    }

    @Test
    void handle_aTextMessage_isIngestedFromTheBsuid_underTheNumbersTenant_deduplicatedByItsId() {
        AtomicReference<UUID> tenantDuringIngest = new AtomicReference<>();
        when(inbound.ingest(any(), anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    tenantDuringIngest.set(TenantContext.getCurrentTenantId().orElse(null));
                    return null;
                });

        service.handle(app.id(), signed(change("""
                {"from":"34600111222","from_user_id":"%s","id":"wamid.A1","timestamp":"1761048000",
                 "type":"text","text":{"body":"Hola, quiero una cita"}}""".formatted(BSUID))));

        verify(inbound).ingest(number.agentId(), "whatsapp", number.id(), IdentityKind.PROVIDER_USER_ID, BSUID,
                "Hola, quiero una cita", number.id() + ":wamid.A1");
        assertThat(tenantDuringIngest.get()).isEqualTo(number.tenantId());
        assertThat(TenantContext.getCurrentTenantId()).isEmpty();
        verifyNoInteractions(outbound, recorder);
    }

    @Test
    void handle_aMessageWithoutFromUserId_fallsBackToTheSingleContactsUserId() {
        service.handle(app.id(), signedWithContacts("""
                {"id":"wamid.A2","type":"text","text":{"body":"Hola"}}""", BSUID));

        verify(inbound).ingest(any(), anyString(), any(), eq(IdentityKind.PROVIDER_USER_ID), eq(BSUID),
                anyString(), anyString());
    }

    @Test
    void handle_aNonTextMessage_isAudited_andAnsweredWithTheTextOnlyReply_neverSilence() {
        when(recorder.recordUnsupportedMessage(number, "image", "wamid.B1", REPLY_TEXT)).thenReturn(Optional.of("Solo texto, por favor."));
        service.handle(app.id(), signed(change("""
                {"from_user_id":"%s","id":"wamid.B1","type":"image","image":{"id":"media-1"}}""".formatted(BSUID))));

        verify(recorder).recordUnsupportedMessage(number, "image", "wamid.B1", REPLY_TEXT);
        ArgumentCaptor<ChannelOutboundMessage> reply = ArgumentCaptor.forClass(ChannelOutboundMessage.class);
        verify(outbound).deliver(reply.capture(), eq(number));
        assertThat(reply.getValue().identityValue()).isEqualTo(BSUID);
        // The recorder's text: the agent's own (V55) when it has one, else the platform default.
        assertThat(reply.getValue().content()).isEqualTo("Solo texto, por favor.");
        assertThat(reply.getValue().lastMessageAt()).isPresent();
        verify(inbound, never()).ingest(any(), anyString(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void handle_aVoiceNote_whereTheInstanceTranscribes_isIngestedByItsMediaId() {
        when(voiceNotes.available()).thenReturn(true);

        service.handle(app.id(), signed(change("""
                {"from_user_id":"%s","id":"wamid.V1","type":"audio",
                 "audio":{"id":"media-9","mime_type":"audio/ogg; codecs=opus","voice":true}}""".formatted(BSUID))));

        verify(inbound).ingestParts(number.agentId(), "whatsapp", number.id(), IdentityKind.PROVIDER_USER_ID, BSUID,
                List.of(MessagePart.Audio.received("audio/ogg; codecs=opus", "media-9", null, null)),
                number.id() + ":wamid.V1");
        verify(outbound).showTypingFor("wamid.V1", number);
        verifyNoInteractions(recorder);
    }

    @Test
    void handle_aVoiceNote_whoseTypingIndicatorIsRefused_isIngestedAllTheSame() {
        when(voiceNotes.available()).thenReturn(true);
        doThrow(new ChannelDeliveryException("WhatsApp typing indicator returned HTTP 400")).when(outbound)
                .showTypingFor(anyString(), any());

        service.handle(app.id(), signed(change("""
                {"from_user_id":"%s","id":"wamid.V2","type":"audio","audio":{"id":"media-3"}}""".formatted(BSUID))));

        verify(inbound).ingestParts(number.agentId(), "whatsapp", number.id(), IdentityKind.PROVIDER_USER_ID, BSUID,
                List.of(MessagePart.Audio.received("audio/ogg", "media-3", null, null)), number.id() + ":wamid.V2");
    }

    @Test
    void handle_aRedeliveredNonTextMessage_whoseReceiptIsTaken_isNotAnsweredAgain() {
        when(recorder.recordUnsupportedMessage(number, "audio", "wamid.B2", REPLY_TEXT)).thenReturn(Optional.empty());

        service.handle(app.id(), signed(change("""
                {"from_user_id":"%s","id":"wamid.B2","type":"audio","audio":{"id":"media-2"}}""".formatted(BSUID))));

        verify(recorder).recordUnsupportedMessage(number, "audio", "wamid.B2", REPLY_TEXT);
        verifyNoInteractions(outbound);
    }

    @Test
    void handle_aNonTextMessageWithoutAnId_cannotBeDeduplicated_soItIsNeitherRecordedNorAnswered() {
        service.handle(app.id(), signed(change("""
                {"from_user_id":"%s","type":"image","image":{"id":"media-3"}}""".formatted(BSUID))));

        verifyNoInteractions(recorder, outbound);
    }

    @Test
    void handle_systemAndGroupMessagesAndMessagesWithoutABsuid_areNeitherIngestedNorAnswered() {
        service.handle(app.id(), signed(change("""
                {"from_user_id":"%s","id":"wamid.S","type":"system",
                 "system":{"type":"user_changed_user_id","previous_user_id":"ES.1"}}""".formatted(BSUID))));
        service.handle(app.id(), signed(change("""
                {"from_user_id":"%s","id":"wamid.G","type":"text","group_id":"g-1","text":{"body":"hola grupo"}}"""
                .formatted(BSUID))));
        service.handle(app.id(), signed(change("""
                {"id":"wamid.N","type":"text","text":{"body":"hola"}}""")));

        verifyNoInteractions(inbound, outbound, recorder);
    }

    @Test
    void handle_eventsForANumberOfAnotherApp_orStatusesOnly_areAcknowledgedAndIgnored() {
        when(numberService.resolveNumber(app.id(), "9999999999")).thenReturn(Optional.empty());

        service.handle(app.id(), signed(envelope("9999999999", """
                "messages":[{"from_user_id":"%s","id":"wamid.X","type":"text","text":{"body":"hola"}}]"""
                .formatted(BSUID))));
        service.handle(app.id(), signed(envelope("1111111111", """
                "statuses":[{"id":"wamid.Y","status":"delivered"}]""")));

        verifyNoInteractions(inbound, outbound, recorder);
    }

    // --- payload builders ---

    private static String change(String message) {
        return envelope("1111111111", "\"messages\":[" + message + "]");
    }

    private static String envelope(String phoneNumberId, String valueMembers) {
        return """
                {"object":"whatsapp_business_account","entry":[{"id":"waba-1","changes":[{"field":"messages",
                 "value":{"messaging_product":"whatsapp","metadata":{"display_phone_number":"34911111111",
                 "phone_number_id":"%s"},%s}}]}]}""".formatted(phoneNumberId, valueMembers);
    }

    private static WebhookRequest signedWithContacts(String message, String userId) {
        String body = envelope("1111111111",
                "\"contacts\":[{\"profile\":{\"name\":\"Ana\"},\"user_id\":\"" + userId + "\"}],"
                        + "\"messages\":[" + message + "]");
        return signed(body);
    }

    private static WebhookRequest signed(String body) {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        return new WebhookRequest(Map.of("X-Hub-Signature-256", WhatsAppSignature.sign(raw, SECRET)), raw);
    }
}
