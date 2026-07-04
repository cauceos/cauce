package dev.cauce.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.cauce.channels.ChannelWebhookService.WebhookResult;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.config.ChannelConfigNotFoundException;
import dev.cauce.channels.spi.ChannelAdapterRegistry;
import dev.cauce.channels.spi.ChannelInboundMessage;
import dev.cauce.channels.spi.InboundChannelAdapter;
import dev.cauce.channels.spi.WebhookRequest;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.orchestration.InboundMessageResult;
import dev.cauce.orchestration.InboundMessageService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChannelWebhookServiceTest {

    private static final WebhookRequest REQUEST = new WebhookRequest(Map.of(), "{}");

    private ChannelConfigService channelConfigService;
    private InboundChannelAdapter adapter;
    private InboundMessageService inboundMessageService;
    private ChannelWebhookService service;

    private ChannelConfig config;

    @BeforeEach
    void setUp() {
        channelConfigService = mock(ChannelConfigService.class);
        adapter = mock(InboundChannelAdapter.class);
        inboundMessageService = mock(InboundMessageService.class);
        when(adapter.channelType()).thenReturn("telegram");
        service = new ChannelWebhookService(channelConfigService,
                new ChannelAdapterRegistry(List.of(adapter)), inboundMessageService);
        config = ChannelConfig.create(UUID.randomUUID(), UUID.randomUUID(), "telegram",
                "token", "hash");
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void handle_unknownOrInactiveConfig_throwsNotFoundAndIngestsNothing() {
        UUID configId = UUID.randomUUID();
        when(channelConfigService.resolveActiveChannelConfig(configId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handle(configId, REQUEST))
                .isInstanceOf(ChannelConfigNotFoundException.class);
        verifyNoInteractions(inboundMessageService);
    }

    @Test
    void handle_failedVerification_throwsAndNeverParsesNorIngests() {
        when(channelConfigService.resolveActiveChannelConfig(config.id()))
                .thenReturn(Optional.of(config));
        when(adapter.verify(REQUEST, config)).thenReturn(false);

        assertThatThrownBy(() -> service.handle(config.id(), REQUEST))
                .isInstanceOf(WebhookAuthenticationException.class);
        verify(adapter, never()).parse(anyString(), any());
        verifyNoInteractions(inboundMessageService);
    }

    @Test
    void handle_unsupportedPayload_returnsIgnoredWithoutIngesting() {
        when(channelConfigService.resolveActiveChannelConfig(config.id()))
                .thenReturn(Optional.of(config));
        when(adapter.verify(REQUEST, config)).thenReturn(true);
        when(adapter.parse(REQUEST.body(), config)).thenReturn(Optional.empty());

        assertThat(service.handle(config.id(), REQUEST)).isEqualTo(WebhookResult.IGNORED);
        verifyNoInteractions(inboundMessageService);
    }

    @Test
    void handle_ingestableMessage_ingestsUnderTheConfigTenantAndClearsContext() {
        ChannelInboundMessage message =
                new ChannelInboundMessage("987654321", "Hola", config.id() + ":1");
        when(channelConfigService.resolveActiveChannelConfig(config.id()))
                .thenReturn(Optional.of(config));
        when(adapter.verify(REQUEST, config)).thenReturn(true);
        when(adapter.parse(REQUEST.body(), config)).thenReturn(Optional.of(message));
        AtomicReference<UUID> contextDuringIngest = new AtomicReference<>();
        when(inboundMessageService.ingest(config.agentId(), "telegram", "987654321", "Hola",
                config.id() + ":1"))
                .thenAnswer(invocation -> {
                    contextDuringIngest.set(TenantContext.getCurrentTenantId().orElse(null));
                    return new InboundMessageResult(UUID.randomUUID(), UUID.randomUUID(),
                            UUID.randomUUID());
                });

        WebhookResult result = service.handle(config.id(), REQUEST);

        assertThat(result).isEqualTo(WebhookResult.ACCEPTED);
        assertThat(contextDuringIngest.get()).isEqualTo(config.tenantId());
        assertThat(TenantContext.getCurrentTenantId()).isEmpty();
    }
}
