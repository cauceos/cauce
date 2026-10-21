package dev.cauce.core.inbound;

import dev.cauce.core.identity.IdentityKind;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Port: a chance to handle an inbound text before it is ingested as a message to an agent (ADR
 * 0007, B8). The channel layer offers every verified, parsed inbound text to each interceptor,
 * under the channel config's tenant context. An interceptor that handles it returns the reply to
 * send back; the text is then not ingested and no agent runs. A redelivery of the same text must
 * be handled again the same way (and must change nothing the second time).
 */
public interface InboundMessageInterceptor {

    /** The reply to send if this interceptor handles {@code text}; empty to let it through. */
    Optional<String> intercept(InboundText text);

    /** An inbound text as the channel received it, with the channel instance it came through. */
    record InboundText(UUID tenantId, UUID channelConfigId, String channelType, IdentityKind identityKind,
                       String identityValue, String content) {

        public InboundText {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(channelConfigId, "channelConfigId");
            Objects.requireNonNull(channelType, "channelType");
            Objects.requireNonNull(identityKind, "identityKind");
            Objects.requireNonNull(identityValue, "identityValue");
            Objects.requireNonNull(content, "content");
        }
    }
}
