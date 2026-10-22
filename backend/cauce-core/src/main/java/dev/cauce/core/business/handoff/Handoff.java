package dev.cauce.core.business.handoff;

import dev.cauce.core.UuidGenerator;
import dev.cauce.core.business.booking.Booking;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A case an agent handed to the owner (ADR 0007, B7): a group, an event, a complaint, a question
 * it cannot answer. It carries what the agent collected — the category, a summary and how to reach
 * the person — and, apart from the agent's summary, the person's own words when the agent quoted
 * them ({@link #customerMessage()}); it stays OPEN until the owner resolves it. The conversation it came from stays
 * OPEN: the agent keeps talking to the person.
 *
 * <p>Pure domain type, immutable. The summary and the contact data are personal business content:
 * they live in the handoffs table, where deletion operates, and never enter the audit chain in
 * clear. {@link #code()} is the short reference the owner and the person use.
 */
public final class Handoff {

    public static final int MAX_CATEGORY_LENGTH = 40;
    public static final int MAX_SUMMARY_LENGTH = 2000;
    public static final int MAX_CUSTOMER_MESSAGE_LENGTH = 1000;

    /** Quotation marks around a quote: the agent's, not the person's words. */
    private static final Pattern QUOTES = Pattern.compile("^[\\s\"'«»“”„]+|[\\s\"'«»“”„]+$");

    /** The lifecycle of a handoff. */
    public enum Status {
        OPEN,
        RESOLVED
    }

    private final UUID id;
    private final UUID tenantId;
    private final String code;
    private final UUID agentId;
    private final UUID conversationId;
    private final UUID identityId;
    private final String category;
    private final String summary;
    private final String contactName;
    private final String contactPhone;
    private final String customerMessage;
    private final Status status;
    private final Instant createdAt;
    private final Instant resolvedAt;

    private Handoff(UUID id, UUID tenantId, String code, UUID agentId, UUID conversationId, UUID identityId,
                    String category, String summary, String contactName, String contactPhone,
                    String customerMessage, Status status, Instant createdAt, Instant resolvedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.code = code;
        this.agentId = agentId;
        this.conversationId = conversationId;
        this.identityId = identityId;
        this.category = category;
        this.summary = summary;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.customerMessage = customerMessage;
        this.status = status;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
    }

    /**
     * What the agent hands over. The phone is optional, and so is {@code customerMessage}: the
     * person's request in their own words, quoted from one of their messages — the adapter checks
     * it is theirs — kept apart from the agent's summary, without the quotation marks it may have
     * been wrapped in.
     */
    public record Request(String category, String summary, String contactName, String contactPhone,
                          String customerMessage) {

        public Request {
            category = required(category, "category", MAX_CATEGORY_LENGTH).toLowerCase(Locale.ROOT);
            summary = required(summary, "summary", MAX_SUMMARY_LENGTH);
            contactName = required(contactName, "contact name", Booking.MAX_CONTACT_NAME_LENGTH);
            contactPhone = contactPhone == null || contactPhone.isBlank() ? null
                    : required(contactPhone, "contact phone", Booking.MAX_CONTACT_PHONE_LENGTH);
            customerMessage = customerMessage == null ? null : QUOTES.matcher(customerMessage).replaceAll("");
            customerMessage = customerMessage == null || customerMessage.isBlank() ? null
                    : required(customerMessage, "customer message", MAX_CUSTOMER_MESSAGE_LENGTH);
        }

        /** A request without the person's own words. */
        public Request(String category, String summary, String contactName, String contactPhone) {
            this(category, summary, contactName, contactPhone, null);
        }
    }

    /** A new OPEN handoff from the conversation of the requester. */
    public static Handoff open(Booking.Requester requester, Request request) {
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(request, "request");
        return new Handoff(UuidGenerator.newV7(), requester.tenantId(), Booking.newCode(), requester.agentId(),
                requester.conversationId(), requester.identityId(), request.category(), request.summary(),
                request.contactName(), request.contactPhone(), request.customerMessage(), Status.OPEN,
                Instant.now().truncatedTo(ChronoUnit.MICROS), null);
    }

    /** Rebuilds a persisted handoff. For the persistence layer only. */
    public static Handoff rehydrate(UUID id, UUID tenantId, String code, UUID agentId, UUID conversationId,
                                    UUID identityId, String category, String summary, String contactName,
                                    String contactPhone, String customerMessage, Status status, Instant createdAt,
                                    Instant resolvedAt) {
        return new Handoff(id, tenantId, code, agentId, conversationId, identityId, category, summary, contactName,
                contactPhone, customerMessage, status, createdAt, resolvedAt);
    }

    /** This handoff resolved now; resolving a resolved one keeps its first resolution. */
    public Handoff resolved() {
        if (status == Status.RESOLVED) {
            return this;
        }
        return new Handoff(id, tenantId, code, agentId, conversationId, identityId, category, summary, contactName,
                contactPhone, customerMessage, Status.RESOLVED, createdAt,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        }
        return stripped;
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

    public String category() {
        return category;
    }

    public String summary() {
        return summary;
    }

    public String contactName() {
        return contactName;
    }

    public Optional<String> contactPhone() {
        return Optional.ofNullable(contactPhone);
    }

    /** The person's request in their own words, if the agent quoted them; never the agent's text. */
    public Optional<String> customerMessage() {
        return Optional.ofNullable(customerMessage);
    }

    public Status status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<Instant> resolvedAt() {
        return Optional.ofNullable(resolvedAt);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof Handoff other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Handoff[id=%s, code=%s, category=%s, status=%s]".formatted(id, code, category, status);
    }
}
