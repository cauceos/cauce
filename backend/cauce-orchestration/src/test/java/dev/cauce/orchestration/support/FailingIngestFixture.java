package dev.cauce.orchestration.support;

import dev.cauce.orchestration.InboundMessageService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test stand-in for a business caller whose transaction fails AFTER a complete, real ingest
 * ran inside it: {@code ingest} joins this method's transaction (REQUIRED propagation), so
 * the thrown exception rolls back the USER message, the invocation, AND the audit outbox
 * capture together. Lets the conduct IT prove "absence of the fact ⇔ absence of the audit
 * record" against the real ingest path, not a placeholder.
 */
@Service
public class FailingIngestFixture {

    private final InboundMessageService inboundMessageService;

    public FailingIngestFixture(InboundMessageService inboundMessageService) {
        this.inboundMessageService = inboundMessageService;
    }

    /** Runs a full real ingest, then fails the surrounding transaction. */
    @Transactional
    public void ingestThenFail(UUID agentId, String channelType, String externalIdentityRef,
                               String content) {
        inboundMessageService.ingest(agentId, channelType, externalIdentityRef, content);
        throw new IllegalStateException("simulated business failure after ingest");
    }
}
