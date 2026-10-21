package dev.cauce.channels.whatsapp;

import dev.cauce.channels.audit.ChannelAuditEvents;
import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.receipt.InboundReceiptRepository;
import dev.cauce.core.UuidGenerator;
import dev.cauce.core.audit.AuditEventRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the WhatsApp facts that are not an ingest (ADR 0006, W5) in their own short
 * transaction, under the number's tenant context set by the caller — the audit recorder joins a
 * transaction, it never opens one.
 */
@Service
public class WhatsAppEventRecorder {

    private final AuditEventRecorder auditRecorder;
    private final InboundReceiptRepository receiptRepository;

    public WhatsAppEventRecorder(AuditEventRecorder auditRecorder, InboundReceiptRepository receiptRepository) {
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
    public boolean recordUnsupportedMessage(ChannelConfig number, String messageType, String providerMessageId) {
        int claimed = receiptRepository.insertIfAbsent(UuidGenerator.newV7(), number.tenantId(), number.id(),
                providerMessageId);
        if (claimed == 0) {
            return false;
        }
        auditRecorder.record(ChannelAuditEvents.unsupportedMessage(number, messageType, providerMessageId));
        return true;
    }
}
