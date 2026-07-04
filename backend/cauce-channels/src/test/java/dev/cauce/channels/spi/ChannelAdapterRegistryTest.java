package dev.cauce.channels.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelAdapterRegistryTest {

    @Test
    void inboundAdapter_registeredType_isFoundByChannelType() {
        InboundChannelAdapter telegram = adapterFor("telegram");
        ChannelAdapterRegistry registry = new ChannelAdapterRegistry(List.of(telegram), List.of());

        assertThat(registry.inboundAdapter("telegram")).contains(telegram);
        assertThat(registry.supportedChannelTypes()).containsExactly("telegram");
    }

    @Test
    void inboundAdapter_unknownType_isEmpty() {
        ChannelAdapterRegistry registry = new ChannelAdapterRegistry(List.of(adapterFor("telegram")), List.of());

        assertThat(registry.inboundAdapter("whatsapp")).isEmpty();
    }

    @Test
    void requireInboundAdapter_unknownType_throwsWithAvailableTypes() {
        ChannelAdapterRegistry registry = new ChannelAdapterRegistry(List.of(adapterFor("telegram")), List.of());

        assertThatThrownBy(() -> registry.requireInboundAdapter("whatsapp"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whatsapp")
                .hasMessageContaining("telegram");
    }

    @Test
    void constructor_duplicateChannelType_isRejected() {
        List<InboundChannelAdapter> duplicates =
                List.of(adapterFor("telegram"), adapterFor("telegram"));

        assertThatThrownBy(() -> new ChannelAdapterRegistry(duplicates, List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void outboundAdapter_registeredType_isFoundByChannelType() {
        OutboundChannelAdapter telegramOutbound = mock(OutboundChannelAdapter.class);
        when(telegramOutbound.channelType()).thenReturn("telegram");
        ChannelAdapterRegistry registry =
                new ChannelAdapterRegistry(List.of(), List.of(telegramOutbound));

        assertThat(registry.outboundAdapter("telegram")).contains(telegramOutbound);
    }

    @Test
    void outboundAdapter_inboundOnlyChannel_isEmpty() {
        ChannelAdapterRegistry registry =
                new ChannelAdapterRegistry(List.of(adapterFor("telegram")), List.of());

        assertThat(registry.outboundAdapter("telegram")).isEmpty();
        assertThat(registry.inboundAdapter("telegram")).isPresent();
    }

    private static InboundChannelAdapter adapterFor(String channelType) {
        InboundChannelAdapter adapter = mock(InboundChannelAdapter.class);
        when(adapter.channelType()).thenReturn(channelType);
        return adapter;
    }
}
