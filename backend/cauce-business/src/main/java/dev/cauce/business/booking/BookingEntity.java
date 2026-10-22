package dev.cauce.business.booking;

import dev.cauce.core.business.booking.Booking;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** JPA mapping of a {@code bookings} row (V36, V40). */
@Entity
@Table(name = "bookings")
public class BookingEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "code", nullable = false, updatable = false, length = 12)
    private String code;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "identity_id", nullable = false, updatable = false)
    private UUID identityId;

    @Column(name = "service_date", nullable = false, updatable = false)
    private LocalDate serviceDate;

    @Column(name = "shift_id", nullable = false, updatable = false, length = 40)
    private String shiftId;

    @Column(name = "requested_time", updatable = false)
    private LocalTime requestedTime;

    @Column(name = "party_size", nullable = false, updatable = false)
    private int partySize;

    @Column(name = "contact_name", nullable = false, length = 100)
    private String contactName;

    @Column(name = "contact_phone", length = 40)
    private String contactPhone;

    @Column(name = "notes", length = 500)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Booking.Status status;

    @Column(name = "replaces_booking_id", updatable = false)
    private UUID replacesBookingId;

    @Column(name = "proposed_in_message_id", nullable = false, updatable = false)
    private UUID proposedInMessageId;

    @Column(name = "hold_expires_at", nullable = false, updatable = false)
    private Instant holdExpiresAt;

    @Column(name = "cancellation_requested_in_message_id")
    private UUID cancellationRequestedInMessageId;

    @Column(name = "cancellation_expires_at")
    private Instant cancellationExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BookingEntity() {
        // for JPA
    }

    public BookingEntity(UUID id, UUID tenantId, String code, UUID agentId, UUID conversationId, UUID identityId,
                         LocalDate serviceDate, String shiftId, LocalTime requestedTime, int partySize,
                         String contactName, String contactPhone, String notes, Booking.Status status, UUID replacesBookingId, UUID proposedInMessageId,
                         Instant holdExpiresAt, UUID cancellationRequestedInMessageId, Instant cancellationExpiresAt,
                         Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.code = code;
        this.agentId = agentId;
        this.conversationId = conversationId;
        this.identityId = identityId;
        this.serviceDate = serviceDate;
        this.shiftId = shiftId;
        this.requestedTime = requestedTime;
        this.partySize = partySize;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.notes = notes;
        this.status = status;
        this.replacesBookingId = replacesBookingId;
        this.proposedInMessageId = proposedInMessageId;
        this.holdExpiresAt = holdExpiresAt;
        this.cancellationRequestedInMessageId = cancellationRequestedInMessageId;
        this.cancellationExpiresAt = cancellationExpiresAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getCode() {
        return code;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getIdentityId() {
        return identityId;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public String getShiftId() {
        return shiftId;
    }

    public LocalTime getRequestedTime() {
        return requestedTime;
    }

    public int getPartySize() {
        return partySize;
    }

    public String getContactName() {
        return contactName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public String getNotes() {
        return notes;
    }

    public Booking.Status getStatus() {
        return status;
    }

    public UUID getReplacesBookingId() {
        return replacesBookingId;
    }

    public UUID getProposedInMessageId() {
        return proposedInMessageId;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public UUID getCancellationRequestedInMessageId() {
        return cancellationRequestedInMessageId;
    }

    public Instant getCancellationExpiresAt() {
        return cancellationExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
