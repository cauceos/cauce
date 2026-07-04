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
 * {@link InboundChannelAdapter} bean, keyed by {@link InboundChannelAdapter#channelType()}
 * — the exact mirror of {@code LlmProviderRegistry}/{@code ToolRegistry}. Two adapters
 * claiming the same channel type fail startup (duplicate key). If no adapter is active the
 * registry is simply empty.
 *
 * <p>Outbound adapters get their own lookup when the outbound commit lands; a channel may
 * legitimately implement only one half.
 */
@Component
public class ChannelAdapterRegistry {

    private final Map<String, InboundChannelAdapter> inboundByType;

    public ChannelAdapterRegistry(List<InboundChannelAdapter> inboundAdapters) {
        this.inboundByType = inboundAdapters.stream()
                .collect(Collectors.toUnmodifiableMap(
                        InboundChannelAdapter::channelType, Function.identity()));
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
}
