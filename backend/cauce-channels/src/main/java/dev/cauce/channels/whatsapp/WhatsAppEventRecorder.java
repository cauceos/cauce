package dev.cauce.channels.whatsapp;

import dev.cauce.channels.audit.ChannelAuditEvents;
import dev.cauce.channels.config.ChannelConfig;
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

    public WhatsAppEventRecorder(AuditEventRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    /** An inbound message of a type Cauce does not read yet: its type and ids, no content. */
    @Transactional
    public void recordUnsupportedMessage(ChannelConfig number, String messageType, String providerMessageId) {
        auditRecorder.record(ChannelAuditEvents.unsupportedMessage(number, messageType, providerMessageId));
    }
}
