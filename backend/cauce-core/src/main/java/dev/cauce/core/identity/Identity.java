package dev.cauce.core.identity;

import dev.cauce.core.UuidGenerator;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The interlocutor's identity on one channel: {@code (channelType, kind, value)}, owned by
 * the tenant that owns the agent the interlocutor talks to (ADR 0004, decision 1). A
 * conversation references an identity instead of storing an opaque string, so a provider
 * that delivers the same person under more than one identifier has somewhere to say so,
 * and the future memory subject has something stable to point at.
 *
 * <p>Pure domain type: no persistence or framework dependencies. Immutable; created via
 * {@link #create} with a time-ordered UUIDv7 id. Uniqueness of
 * {@code (tenantId, channelType, kind, value)} is a persistence invariant (the unique index
 * is the arbiter of the resolve-or-create upsert), not something this class can enforce.
 *
 * <p><strong>The core stores and compares. It does not interpret.</strong> {@code value} is
 * required to be non-blank and is stripped of surrounding whitespace, exactly what the
 * conversation did with its opaque reference; no format check, no normalisation. Knowing that
 * two spellings of a telephone number are the same number is channel knowledge and lives in
 * the adapter that produced the value. {@code channelType} is a free-form identifier whose
 * valid values the channel SPI owns, so it is a {@code String}, not an enum; {@code kind} is
 * a closed domain vocabulary and is.
 */
public final class Identity {

    private final UUID id;
    private final UUID tenantId;
    private final String channelType;
    private final IdentityKind kind;
    private final String value;
    private final Instant createdAt;

    private Identity(UUID id, UUID tenantId, String channelType, IdentityKind kind, String value,
                     Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.channelType = channelType;
        this.kind = kind;
        this.value = value;
        this.createdAt = createdAt;
    }

    /**
     * Mints a new identity owned by {@code tenantId} (the tenant of the agent, never the
     * acting tenant of the request: a partner ingesting on behalf of its client creates the
     * client's identity). {@code channelType} and {@code value} must be non-blank and are
     * stripped; nothing else is checked.
     */
    public static Identity create(UUID tenantId, String channelType, IdentityKind kind, String value) {
        return new Identity(
                UuidGenerator.newV7(),
                Objects.requireNonNull(tenantId, "tenantId"),
                requireText(channelType, "channelType"),
                Objects.requireNonNull(kind, "kind"),
                requireText(value, "value"),
                Instant.now());
    }

    /** Rebuilds an identity from already-persisted state. For the persistence layer only. */
    public static Identity rehydrate(UUID id, UUID tenantId, String channelType, IdentityKind kind,
                                     String value, Instant createdAt) {
        return new Identity(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(tenantId, "tenantId"),
                Objects.requireNonNull(channelType, "channelType"),
                Objects.requireNonNull(kind, "kind"),
                Objects.requireNonNull(value, "value"),
                Objects.requireNonNull(createdAt, "createdAt"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String channelType() {
        return channelType;
    }

    public IdentityKind kind() {
        return kind;
    }

    public String value() {
        return value;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Identity other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        // value is intentionally omitted (personal data).
        return "Identity[id=%s, tenantId=%s, channelType=%s, kind=%s]"
                .formatted(id, tenantId, channelType, kind);
    }
}
