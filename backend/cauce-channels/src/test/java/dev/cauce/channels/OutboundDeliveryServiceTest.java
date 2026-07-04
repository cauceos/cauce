package dev.cauce.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelAdapterRegistry;
import dev.cauce.channels.spi.ChannelDeliveryException;
import dev.cauce.channels.spi.ChannelOutboundMessage;
import dev.cauce.channels.spi.OutboundChannelAdapter;
import dev.cauce.core.conversation.Conversation;
import dev.cauce.core.message.Message;
import dev.cauce.core.message.MessageRole;
import dev.cauce.core.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * Unit tests with a {@link SyncTaskExecutor} so the "async" delivery runs deterministically
 * on the test thread.
 */
class OutboundDeliveryServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    private OutboundChannelAdapter telegramOutbound;
    private ChannelConfigService channelConfigService;
    private OutboundDeliveryService service;

    private Conversation conversation;
    private Message reply;
    private ChannelConfig config;

    @BeforeEach
    void setUp() {
        telegramOutbound = mock(OutboundChannelAdapter.class);
        when(telegramOutbound.channelType()).thenReturn("telegram");
        channelConfigService = mock(ChannelConfigService.class);
        service = new OutboundDeliveryService(
                new ChannelAdapterRegistry(List.of(), List.of(telegramOutbound)),
                channelConfigService, new SyncTaskExecutor());
        conversation = Conversation.start(AGENT_ID, "telegram", "987654321");
        reply = Message.from(conversation.id(), MessageRole.AGENT, "Hola, soy un agente");
        config = ChannelConfig.create(TENANT_ID, AGENT_ID, "telegram", "12345:token", "hash");
        TenantContext.setCurrentTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void dispatchAgentReply_channelWithoutOutboundAdapter_isNoOp() {
        Conversation apiConversation = Conversation.start(AGENT_ID, "api", "user-1");

        service.dispatchAgentReply(apiConversation, reply);

        verify(telegramOutbound, never()).deliver(any(), any());
    }

    @Test
    void dispatchAgentReply_singleActiveConfig_deliversToTheConversationIdentity() {
        AtomicReference<UUID> tenantDuringDelivery = new AtomicReference<>();
        when(channelConfigService.findActiveConfigs(AGENT_ID, "telegram"))
                .thenReturn(List.of(config));
        doAnswer(invocation -> {
            tenantDuringDelivery.set(TenantContext.getCurrentTenantId().orElse(null));
            return null;
        }).when(telegramOutbound).deliver(any(), any());

        service.dispatchAgentReply(conversation, reply);

        verify(telegramOutbound).deliver(
                new ChannelOutboundMessage("987654321", "Hola, soy un agente"), config);
        assertThat(tenantDuringDelivery.get()).isEqualTo(TENANT_ID);
        // The task cleared its own context; the dispatching thread ends clean (SyncTaskExecutor
        // runs it on the same thread here).
        assertThat(TenantContext.getCurrentTenantId()).isEmpty();
    }

    @Test
    void dispatchAgentReply_noActiveConfig_skipsDelivery() {
        when(channelConfigService.findActiveConfigs(AGENT_ID, "telegram")).thenReturn(List.of());

        service.dispatchAgentReply(conversation, reply);

        verify(telegramOutbound, never()).deliver(any(), any());
    }

    @Test
    void dispatchAgentReply_ambiguousConfigs_skipsDelivery() {
        ChannelConfig second = ChannelConfig.create(TENANT_ID, AGENT_ID, "telegram", "other", "h");
        when(channelConfigService.findActiveConfigs(AGENT_ID, "telegram"))
                .thenReturn(List.of(config, second));

        service.dispatchAgentReply(conversation, reply);

        verify(telegramOutbound, never()).deliver(any(), any());
    }

    @Test
    void dispatchAgentReply_deliveryFailure_isSwallowed() {
        when(channelConfigService.findActiveConfigs(AGENT_ID, "telegram"))
                .thenReturn(List.of(config));
        doThrow(new ChannelDeliveryException("Telegram is down"))
                .when(telegramOutbound).deliver(any(), any());

        assertThatCode(() -> service.dispatchAgentReply(conversation, reply))
                .doesNotThrowAnyException();
    }

    @Test
    void dispatchAgentReply_withoutTenantContext_skipsDelivery() {
        TenantContext.clear();

        service.dispatchAgentReply(conversation, reply);

        verify(telegramOutbound, never()).deliver(any(), any());
    }
}
