package dev.cauce.channels.spi;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Registry of the channel adapters available at runtime. Spring injects every
 * {@link InboundChannelAdapter} and {@link OutboundChannelAdapter} bean, each keyed by its
 * channel type — the exact mirror of {@code LlmProviderRegistry}/{@code ToolRegistry}. Two
 * adapters claiming the same channel type on the same half fail startup (duplicate key). A
 * channel may legitimately implement only one half: the built-in {@code "api"} channel has
 * neither, and an inbound-only channel simply has no outbound entry (delivery is a clean
 * no-op).
 */
@Component
public class ChannelAdapterRegistry {

    private final Map<String, InboundChannelAdapter> inboundByType;
    private final Map<String, OutboundChannelAdapter> outboundByType;

    public ChannelAdapterRegistry(List<InboundChannelAdapter> inboundAdapters,
                                  List<OutboundChannelAdapter> outboundAdapters) {
        this.inboundByType = inboundAdapters.stream()
                .collect(Collectors.toUnmodifiableMap(
                        InboundChannelAdapter::channelType, Function.identity()));
        this.outboundByType = outboundAdapters.stream()
                .collect(Collectors.toUnmodifiableMap(
                        OutboundChannelAdapter::channelType, Function.identity()));
    }

    /** The inbound adapter for {@code channelType}, if registered. */
    public Optional<InboundChannelAdapter> inboundAdapter(String channelType) {
        return Optional.ofNullable(inboundByType.get(channelType));
    }

    /**
     * The inbound adapter for {@code channelType}.
     *
     * @throws IllegalStateException if no adapter is registered under that type — a
     *     deployment gap (a config row exists for a channel this runtime cannot serve)
     */
    public InboundChannelAdapter requireInboundAdapter(String channelType) {
        InboundChannelAdapter adapter = inboundByType.get(channelType);
        if (adapter == null) {
            throw new IllegalStateException("No inbound channel adapter registered for type '"
                    + channelType + "'. Available: " + supportedChannelTypes());
        }
        return adapter;
    }

    /** The channel types with a registered inbound adapter. */
    public Set<String> supportedChannelTypes() {
        return inboundByType.keySet();
    }

    /**
     * The outbound adapter for {@code channelType}, if registered. Empty for channels
     * without a delivery half (the caller treats that as a no-op, not an error).
     */
    public Optional<OutboundChannelAdapter> outboundAdapter(String channelType) {
        return Optional.ofNullable(outboundByType.get(channelType));
    }
}
