package dev.cauce.channels;

import dev.cauce.channels.audit.ChannelAuditEvents;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.receipt.InboundReceiptRepository;
import dev.cauce.core.UuidGenerator;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.memory.agent.AgentEntity;
import dev.cauce.memory.agent.AgentRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records an inbound message a channel delivered but Cauce does not read (ADR 0006, W5) in its own
 * short transaction, under the config's tenant context set by the caller — the audit recorder joins
 * a transaction, it never opens one. Channel-neutral: the channel's message type and the provider's
 * message id are opaque strings here.
 */
@Service
public class UnsupportedMessageRecorder {

    private final AuditEventRecorder auditRecorder;
    private final InboundReceiptRepository receiptRepository;
    private final AgentRepository agentRepository;

    public UnsupportedMessageRecorder(AuditEventRecorder auditRecorder, InboundReceiptRepository receiptRepository,
                                      AgentRepository agentRepository) {
        this.auditRecorder = auditRecorder;
        this.receiptRepository = receiptRepository;
        this.agentRepository = agentRepository;
    }

    /**
     * An inbound message of a type Cauce does not read yet: claims its receipt and, only for the
     * first delivery, audits its type and ids (no content). Returns the text to answer it with —
     * the config's agent's own reply (V55), else {@code platformReply} — so the caller replies
     * exactly once: a redelivery, sequential or concurrent, finds the receipt taken (V32 unique
     * constraint) and gets empty, with no second record.
     */
    @Transactional
    public Optional<String> recordUnsupportedMessage(ChannelConfig config, String messageType,
                                                     String providerMessageId, String platformReply) {
        int claimed = receiptRepository.insertIfAbsent(UuidGenerator.newV7(), config.tenantId(), config.id(),
                providerMessageId);
        if (claimed == 0) {
            return Optional.empty();
        }
        auditRecorder.record(ChannelAuditEvents.unsupportedMessage(config, messageType, providerMessageId));
        return Optional.of(agentRepository.findById(config.agentId())
                .map(AgentEntity::getUnsupportedMessageReply)
                .orElse(platformReply));
    }
}
