package dev.cauce.channels;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.spi.ChannelAdapterRegistry;
import dev.cauce.channels.spi.ChannelDeliveryException;
import dev.cauce.channels.spi.ChannelOutboundMessage;
import dev.cauce.channels.spi.OutboundChannelAdapter;
import dev.cauce.core.identity.IdentityKind;
import dev.cauce.core.inbound.InboundMessageInterceptor;
import dev.cauce.core.inbound.InboundMessageInterceptor.InboundText;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Offers a verified, parsed inbound text to the {@link InboundMessageInterceptor}s before it is
 * ingested (ADR 0007, B8), and answers it directly when one handles it: the text then reaches no
 * agent. Called by every inbound flow under the channel config's tenant context, which the
 * interceptors' reads and writes run under.
 */
@Component
public class InboundInterception {

    private static final Logger log = LoggerFactory.getLogger(InboundInterception.class);

    private final ObjectProvider<InboundMessageInterceptor> interceptors;
    private final ChannelAdapterRegistry adapterRegistry;
    private final Clock clock;

    public InboundInterception(ObjectProvider<InboundMessageInterceptor> interceptors,
                               ChannelAdapterRegistry adapterRegistry,
                               Clock clock) {
        this.interceptors = interceptors;
        this.adapterRegistry = adapterRegistry;
        this.clock = clock;
    }

    /**
     * Whether an interceptor handled {@code content}; if so, its reply has been sent back to the
     * interlocutor (best-effort: a failed reply is logged, the text is still handled).
     */
    public boolean intercepted(ChannelConfig config, IdentityKind kind, String identityValue, String content) {
        InboundText text = new InboundText(config.tenantId(), config.id(), config.channelType(), kind, identityValue,
                content);
        List<InboundMessageInterceptor> all = interceptors.orderedStream().toList();
        for (InboundMessageInterceptor interceptor : all) {
            Optional<String> reply = interceptor.intercept(text);
            if (reply.isPresent()) {
                reply(config, kind, identityValue, reply.get());
                return true;
            }
        }
        return false;
    }

    private void reply(ChannelConfig config, IdentityKind kind, String identityValue, String reply) {
        Optional<OutboundChannelAdapter> adapter = adapterRegistry.outboundAdapter(config.channelType());
        if (adapter.isEmpty()) {
            return;
        }
        try {
            // The interlocutor has just written, so any reply window is open (measured, like every
            // window, on the database clock: ADR 0008).
            adapter.get().deliver(new ChannelOutboundMessage(kind, identityValue, reply, clock.instant()), config);
        } catch (ChannelDeliveryException e) {
            log.warn("Could not answer an intercepted message through config {}: {}", config.id(), e.getMessage());
        }
    }
}
