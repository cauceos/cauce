package dev.cauce.channels;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelAdapterRegistry;
import dev.cauce.channels.spi.ChannelDeliveryException;
import dev.cauce.channels.spi.ChannelOutboundMessage;
import dev.cauce.channels.spi.OutboundChannelAdapter;
import dev.cauce.channels.spi.OutsideReplyWindowException;
import dev.cauce.core.business.owner.OwnerNotifier;
import dev.cauce.core.identity.Identity;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The channel layer's {@link OwnerNotifier} (ADR 0007, B9): sends a proactive message to a business
 * owner through the owner bot they linked, synchronously, through the ordinary outbound SPI.
 *
 * <p>The reply window is measured from the owner's last message through that bot, or the moment
 * they linked if they wrote nothing since — the later of the two. A channel that refuses outside its
 * window (WhatsApp, ADR 0006) makes the notice NOT_SENT with {@code outside_reply_window}: no
 * template is sent instead. A channel config no longer active, or an identity no longer visible, is
 * NOT_SENT with {@code channel_unavailable}. Any other refusal is a FAILED attempt, retried by the
 * dispatcher. Runs under the business's tenant context (the dispatcher sets it).
 */
@Service
public class OwnerNotificationSender implements OwnerNotifier {

    private static final Logger log = LoggerFactory.getLogger(OwnerNotificationSender.class);

    private final ChannelConfigService channelConfigService;
    private final ChannelAdapterRegistry adapterRegistry;
    private final InterlocutorLookup interlocutorLookup;

    public OwnerNotificationSender(ChannelConfigService channelConfigService, ChannelAdapterRegistry adapterRegistry,
                                   InterlocutorLookup interlocutorLookup) {
        this.channelConfigService = channelConfigService;
        this.adapterRegistry = adapterRegistry;
        this.interlocutorLookup = interlocutorLookup;
    }

    @Override
    public Delivery send(Recipient recipient, String text) {
        Optional<ChannelConfig> config = channelConfigService.findActiveConfig(recipient.channelConfigId());
        if (config.isEmpty()) {
            return Delivery.notSent(Delivery.CHANNEL_UNAVAILABLE);
        }
        Optional<OutboundChannelAdapter> adapter = adapterRegistry.outboundAdapter(config.get().channelType());
        Optional<Identity> owner = interlocutorLookup.find(recipient.identityId());
        if (adapter.isEmpty() || owner.isEmpty()) {
            return Delivery.notSent(Delivery.CHANNEL_UNAVAILABLE);
        }
        Instant lastContact = interlocutorLookup.lastMessageAt(recipient.identityId(), recipient.channelConfigId())
                .filter(at -> at.isAfter(recipient.linkedAt()))
                .orElse(recipient.linkedAt());
        try {
            adapter.get().deliver(new ChannelOutboundMessage(owner.get().kind(), owner.get().value(), text, lastContact),
                    config.get());
            return Delivery.sent();
        } catch (OutsideReplyWindowException e) {
            log.info("Owner notice through config {} not sent: {}", recipient.channelConfigId(), e.getMessage());
            return Delivery.notSent(Delivery.OUTSIDE_REPLY_WINDOW);
        } catch (ChannelDeliveryException e) {
            log.warn("Owner notice through config {} failed: {}", recipient.channelConfigId(), e.getMessage());
            return Delivery.failed(e.getMessage());
        }
    }
}
