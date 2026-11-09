package dev.cauce.api.channel;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.config.ChannelConfigStatus;
import java.util.UUID;

/** A channel instance's status after the emergency switch: never its credential or secrets. */
public record ChannelStatusResponse(UUID id, UUID agentId, String channelType, ChannelConfigStatus status) {

    static ChannelStatusResponse from(ChannelConfig config) {
        return new ChannelStatusResponse(config.id(), config.agentId(), config.channelType(), config.status());
    }
}
