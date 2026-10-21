package dev.cauce.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelAdapterRegistry;
import dev.cauce.channels.spi.ChannelDeliveryException;
import dev.cauce.channels.spi.ChannelOutboundMessage;
import dev.cauce.channels.spi.OutboundChannelAdapter;
import dev.cauce.channels.spi.OutsideReplyWindowException;
import dev.cauce.core.business.owner.OwnerNotifier.Delivery;
import dev.cauce.core.business.owner.OwnerNotifier.Outcome;
import dev.cauce.core.business.owner.OwnerNotifier.Recipient;
import dev.cauce.core.identity.Identity;
import dev.cauce.core.identity.IdentityKind;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OwnerNotificationSenderTest {

    private final ChannelConfigService configs = mock(ChannelConfigService.class);
    private final InterlocutorLookup lookup = mock(InterlocutorLookup.class);
    private final OutboundChannelAdapter adapter = mock(OutboundChannelAdapter.class);
    private final ChannelConfig ownerBot = ChannelConfig.create(UUID.randomUUID(), UUID.randomUUID(), "telegram",
            "token", "hash");
    private final Identity owner = Identity.create(ownerBot.tenantId(), "telegram", IdentityKind.PROVIDER_USER_ID,
            "555");
    private final Instant linkedAt = Instant.now().minus(Duration.ofDays(3));
    private final Recipient recipient = new Recipient(ownerBot.id(), owner.id(), linkedAt);
    private OwnerNotificationSender sender;

    @BeforeEach
    void setUp() {
        when(adapter.channelType()).thenReturn("telegram");
        sender = new OwnerNotificationSender(configs, new ChannelAdapterRegistry(List.of(), List.of(adapter)), lookup);
        when(configs.findActiveConfig(ownerBot.id())).thenReturn(Optional.of(ownerBot));
        when(lookup.find(owner.id())).thenReturn(Optional.of(owner));
        when(lookup.lastMessageAt(owner.id(), ownerBot.id())).thenReturn(Optional.empty());
    }

    @Test
    void send_deliversToTheOwnersChat_measuringTheWindowFromTheLaterOfLinkAndLastMessage() {
        Instant lastMessage = Instant.now().minus(Duration.ofHours(1));
        when(lookup.lastMessageAt(owner.id(), ownerBot.id())).thenReturn(Optional.of(lastMessage));

        assertThat(sender.send(recipient, "Nuevo caso").outcome()).isEqualTo(Outcome.SENT);

        ArgumentCaptor<ChannelOutboundMessage> sent = ArgumentCaptor.forClass(ChannelOutboundMessage.class);
        verify(adapter).deliver(sent.capture(), any());
        assertThat(sent.getValue().identityValue()).isEqualTo("555");
        assertThat(sent.getValue().lastMessageAt()).contains(lastMessage);
    }

    @Test
    void send_withoutAnyLaterMessage_measuresFromTheLink() {
        sender.send(recipient, "Nuevo caso");

        ArgumentCaptor<ChannelOutboundMessage> sent = ArgumentCaptor.forClass(ChannelOutboundMessage.class);
        verify(adapter).deliver(sent.capture(), any());
        assertThat(sent.getValue().lastMessageAt()).contains(linkedAt);
    }

    @Test
    void send_outsideTheReplyWindow_isNotSent_andAnyOtherRefusalFails() {
        doThrow(new OutsideReplyWindowException("closed")).when(adapter).deliver(any(), any());
        assertThat(sender.send(recipient, "x")).isEqualTo(Delivery.notSent(Delivery.OUTSIDE_REPLY_WINDOW));

        doThrow(new ChannelDeliveryException("HTTP 403")).when(adapter).deliver(any(), any());
        assertThat(sender.send(recipient, "x")).isEqualTo(Delivery.failed("HTTP 403"));
    }

    @Test
    void send_throughAnInactiveBot_isNotSent() {
        when(configs.findActiveConfig(ownerBot.id())).thenReturn(Optional.empty());

        assertThat(sender.send(recipient, "x")).isEqualTo(Delivery.notSent(Delivery.CHANNEL_UNAVAILABLE));
    }
}
