package dev.cauce.channels;

import dev.cauce.channels.audit.ChannelAuditEvents;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.receipt.InboundReceiptRepository;
import dev.cauce.core.UuidGenerator;
import dev.cauce.core.audit.AuditEventRecorder;
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

    public UnsupportedMessageRecorder(AuditEventRecorder auditRecorder, InboundReceiptRepository receiptRepository) {
        this.auditRecorder = auditRecorder;
        this.receiptRepository = receiptRepository;
    }

    /**
     * An inbound message of a type Cauce does not read yet: claims its receipt and, only for the
     * first delivery, audits its type and ids (no content). Returns whether this call handled it,
     * so the caller replies exactly once: a redelivery — sequential or concurrent — finds the
     * receipt taken (V32 unique constraint) and returns {@code false}, with no second record.
     */
    @Transactional
    public boolean recordUnsupportedMessage(ChannelConfig config, String messageType, String providerMessageId) {
        int claimed = receiptRepository.insertIfAbsent(UuidGenerator.newV7(), config.tenantId(), config.id(),
                providerMessageId);
        if (claimed == 0) {
            return false;
        }
        auditRecorder.record(ChannelAuditEvents.unsupportedMessage(config, messageType, providerMessageId));
        return true;
    }
}
