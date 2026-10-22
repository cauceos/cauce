package dev.cauce.core.business.booking;

import dev.cauce.core.UuidGenerator;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A booking of one shift on one date (ADR 0007, B6), owned by the interlocutor who made it.
 * Capacity is counted per shift; the {@link #time() time} the person asked for, when they named
 * one, is kept within its shift so it can be read back and prepared for.
 *
 * <p>Every change goes through a proposal that the person confirms in a later turn:
 * <ul>
 *   <li>a new booking, or a change of one, is first {@link Status#HELD}: it holds capacity until
 *       {@link #holdExpiresAt()} and remembers the message it was proposed in;</li>
 *   <li>a cancellation is first requested on a {@link Status#CONFIRMED} booking, with its own
 *       expiry and message;</li>
 *   <li>confirming applies the pending change, and is refused from the message it was proposed
 *       in ({@link BookingRefusal.Reason#SAME_TURN}) and after it expired.</li>
 * </ul>
 * A change is a new booking that {@link #replacesBookingId() replaces} a confirmed one; confirming
 * it cancels the one it replaces. {@link #code()} is the short reference people use.
 *
 * <p>Pure domain type, immutable. The name, phone and notes are personal data: they live in the
 * bookings table, where deletion operates, and never enter the audit chain in clear.
 */
public final class Booking {

    public static final int MAX_CONTACT_NAME_LENGTH = 100;
    public static final int MAX_CONTACT_PHONE_LENGTH = 40;
    public static final int MAX_NOTES_LENGTH = 500;
    public static final int CODE_LENGTH = 6;

    /** Without 0/O, 1/I/L: a code read over the phone is not misheard. */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The lifecycle of a booking. */
    public enum Status {
        /** Proposed, holding capacity until it expires or is confirmed. */
        HELD,
        /** Confirmed by the person. */
        CONFIRMED,
        /** Cancelled by the person, or replaced by a confirmed change. */
        CANCELLED
    }

    /** What a booking is waiting for the person to confirm. */
    public enum Pending {
        CONFIRMATION,
        CANCELLATION
    }

    private final UUID id;
    private final UUID tenantId;
    private final String code;
    private final UUID agentId;
    private final UUID conversationId;
    private final UUID identityId;
    private final LocalDate date;
    private final String shiftId;
    private final LocalTime time;
    private final int partySize;
    private final String contactName;
    private final String contactPhone;
    private final String notes;
    private final Status status;
    private final UUID replacesBookingId;
    private final UUID proposedInMessageId;
    private final Instant holdExpiresAt;
    private final UUID cancellationRequestedInMessageId;
    private final Instant cancellationExpiresAt;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Booking(UUID id, UUID tenantId, String code, UUID agentId, UUID conversationId, UUID identityId,
                    LocalDate date, String shiftId, LocalTime time, int partySize, String contactName,
                    String contactPhone, String notes, Status status, UUID replacesBookingId,
                    UUID proposedInMessageId, Instant holdExpiresAt, UUID cancellationRequestedInMessageId,
                    Instant cancellationExpiresAt, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.code = code;
        this.agentId = agentId;
        this.conversationId = conversationId;
        this.identityId = identityId;
        this.date = date;
        this.shiftId = shiftId;
        this.time = time;
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

    /** Who a proposal is made for and in which turn: taken from the conversation, never from the model. */
    public record Requester(UUID tenantId, UUID agentId, UUID conversationId, UUID identityId, UUID messageId) {

        public Requester {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(identityId, "identityId");
            Objects.requireNonNull(messageId, "messageId");
        }
    }

    /**
     * What is being booked. {@code time} is the time the person asked for, to the minute, or null
     * when they named only the shift; whether it falls within the shift is checked against the
     * agenda. Name, phone and notes are stripped; phone and notes are optional.
     */
    public record Details(LocalDate date, String shiftId, LocalTime time, int partySize, String contactName,
                          String contactPhone, String notes) {

        public Details {
            Objects.requireNonNull(date, "date");
            if (shiftId == null || shiftId.isBlank()) {
                throw new IllegalArgumentException("the shift is required");
            }
            shiftId = shiftId.strip();
            time = time == null ? null : time.truncatedTo(ChronoUnit.MINUTES);
            if (contactName == null || contactName.isBlank()) {
                throw new IllegalArgumentException("the contact name is required");
            }
            contactName = bounded(contactName, "contact name", MAX_CONTACT_NAME_LENGTH);
            contactPhone = contactPhone == null || contactPhone.isBlank() ? null
                    : bounded(contactPhone, "contact phone", MAX_CONTACT_PHONE_LENGTH);
            notes = notes == null || notes.isBlank() ? null : bounded(notes, "notes", MAX_NOTES_LENGTH);
        }
    }

    /**
     * A new proposal, HELD until {@code holdExpiresAt}. {@code replacesBookingId} is the confirmed
     * booking this one changes, or null for a new booking.
     */
    public static Booking propose(Requester requester, Details details, Instant holdExpiresAt, UUID replacesBookingId) {
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(details, "details");
        Instant now = now();
        return new Booking(UuidGenerator.newV7(), requester.tenantId(), newCode(), requester.agentId(),
                requester.conversationId(), requester.identityId(), details.date(), details.shiftId(),
                details.time(), details.partySize(), details.contactName(), details.contactPhone(), details.notes(),
                Status.HELD, replacesBookingId, requester.messageId(), Objects.requireNonNull(holdExpiresAt, "holdExpiresAt"),
                null, null, now, now);
    }

    /** Rebuilds a persisted booking. For the persistence layer only. */
    public static Booking rehydrate(UUID id, UUID tenantId, String code, UUID agentId, UUID conversationId,
                                    UUID identityId, LocalDate date, String shiftId, LocalTime time,
                                    int partySize, String contactName, String contactPhone, String notes,
                                    Status status,
                                    UUID replacesBookingId, UUID proposedInMessageId, Instant holdExpiresAt,
                                    UUID cancellationRequestedInMessageId, Instant cancellationExpiresAt,
                                    Instant createdAt, Instant updatedAt) {
        return new Booking(id, tenantId, code, agentId, conversationId, identityId, date, shiftId, time, partySize,
                contactName, contactPhone, notes, status, replacesBookingId, proposedInMessageId, holdExpiresAt,
                cancellationRequestedInMessageId, cancellationExpiresAt, createdAt, updatedAt);
    }

    /** This booking with a cancellation requested in {@code messageId}, pending until {@code expiresAt}. */
    public Booking withCancellationRequested(UUID messageId, Instant expiresAt) {
        if (status != Status.CONFIRMED) {
            throw new IllegalStateException("only a confirmed booking can be cancelled");
        }
        return copy(status, Objects.requireNonNull(messageId, "messageId"), Objects.requireNonNull(expiresAt));
    }

    /** This booking confirmed. */
    public Booking confirmed() {
        if (status != Status.HELD) {
            throw new IllegalStateException("only a held booking can be confirmed");
        }
        return copy(Status.CONFIRMED, null, null);
    }

    /** This booking cancelled. */
    public Booking cancelled() {
        return copy(Status.CANCELLED, null, null);
    }

    private Booking copy(Status newStatus, UUID cancellationMessageId, Instant cancellationExpiry) {
        return new Booking(id, tenantId, code, agentId, conversationId, identityId, date, shiftId, time, partySize,
                contactName, contactPhone, notes, newStatus, replacesBookingId, proposedInMessageId, holdExpiresAt,
                cancellationMessageId, cancellationExpiry, createdAt, now());
    }

    /** What this booking waits for the person to confirm at {@code now}, if anything. */
    public Optional<Pending> pendingAt(Instant now) {
        if (status == Status.HELD && holdExpiresAt.isAfter(now)) {
            return Optional.of(Pending.CONFIRMATION);
        }
        if (status == Status.CONFIRMED && cancellationExpiresAt != null && cancellationExpiresAt.isAfter(now)) {
            return Optional.of(Pending.CANCELLATION);
        }
        return Optional.empty();
    }

    /** Whether this booking takes capacity at {@code now}: confirmed, or held and not expired. */
    public boolean takesCapacityAt(Instant now) {
        return status == Status.CONFIRMED || status == Status.HELD && holdExpiresAt.isAfter(now);
    }

    /**
     * Why confirming the pending change of this booking from {@code messageId} at {@code now} is
     * refused, or empty when it may be confirmed.
     */
    public Optional<BookingRefusal> confirmationRefusal(UUID messageId, Instant now) {
        boolean heldPending = status == Status.HELD;
        boolean cancellationPending = status == Status.CONFIRMED && cancellationRequestedInMessageId != null;
        if (!heldPending && !cancellationPending) {
            return Optional.of(new BookingRefusal(BookingRefusal.Reason.NOT_PENDING,
                    "booking " + code + " has nothing waiting for confirmation"));
        }
        if (pendingAt(now).isEmpty()) {
            return Optional.of(new BookingRefusal(BookingRefusal.Reason.EXPIRED,
                    "the proposal for " + code + " expired: propose it again"));
        }
        UUID proposedIn = heldPending ? proposedInMessageId : cancellationRequestedInMessageId;
        if (proposedIn.equals(messageId)) {
            return Optional.of(new BookingRefusal(BookingRefusal.Reason.SAME_TURN, "read the details of " + code
                    + " back to the person and ask them to confirm; confirm only after they reply"));
        }
        return Optional.empty();
    }

    /** A fresh booking code. Unique per business: the adapter retries on the rare collision. */
    public static String newCode() {
        char[] code = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            code[i] = CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)];
        }
        return new String(code);
    }

    private static String bounded(String value, String field, int max) {
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        }
        return stripped;
    }

    /** Microseconds: what the database keeps, so a saved booking equals the one read back. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String code() {
        return code;
    }

    public UUID agentId() {
        return agentId;
    }

    public UUID conversationId() {
        return conversationId;
    }

    public UUID identityId() {
        return identityId;
    }

    public LocalDate date() {
        return date;
    }

    public String shiftId() {
        return shiftId;
    }

    /** The time the person asked for, within the shift; empty when they named only the shift. */
    public Optional<LocalTime> time() {
        return Optional.ofNullable(time);
    }

    public int partySize() {
        return partySize;
    }

    public String contactName() {
        return contactName;
    }

    public Optional<String> contactPhone() {
        return Optional.ofNullable(contactPhone);
    }

    public Optional<String> notes() {
        return Optional.ofNullable(notes);
    }

    public Status status() {
        return status;
    }

    public Optional<UUID> replacesBookingId() {
        return Optional.ofNullable(replacesBookingId);
    }

    public UUID proposedInMessageId() {
        return proposedInMessageId;
    }

    public Instant holdExpiresAt() {
        return holdExpiresAt;
    }

    public Optional<UUID> cancellationRequestedInMessageId() {
        return Optional.ofNullable(cancellationRequestedInMessageId);
    }

    public Optional<Instant> cancellationExpiresAt() {
        return Optional.ofNullable(cancellationExpiresAt);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof Booking other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        // Contact data is intentionally omitted.
        return "Booking[id=%s, code=%s, date=%s, shift=%s, time=%s, party=%d, status=%s]"
                .formatted(id, code, date, shiftId, time, partySize, status);
    }
}
