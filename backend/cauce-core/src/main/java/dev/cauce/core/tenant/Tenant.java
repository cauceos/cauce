package dev.cauce.core.tenant;

import dev.cauce.core.UuidGenerator;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A tenant in the multi-tier hierarchy (operator &rarr; partner &rarr; client).
 *
 * <p>Pure domain type: no persistence annotations, no framework dependencies.
 * Instances are immutable and created through the {@code operator}, {@code partner},
 * and {@code client} factory methods, which mint a time-ordered UUIDv7 id and set
 * the timestamps. The factories enforce the structural shape of the hierarchy that
 * can be checked in memory (an operator has no parent; a partner/client must have
 * one); the parent's actual tier is enforced by the database.
 */
public final class Tenant {

    private final UUID id;
    private final UUID parentTenantId; // null only for an OPERATOR
    private final Tier tier;
    private final String name;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Tenant(UUID id, UUID parentTenantId, Tier tier, String name,
                   Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.parentTenantId = parentTenantId;
        this.tier = tier;
        this.name = name;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Creates a top-level operator tenant (no parent). */
    public static Tenant operator(String name) {
        Instant now = Instant.now();
        return new Tenant(UuidGenerator.newV7(), null, Tier.OPERATOR, requireName(name), now, now);
    }

    /** Creates a partner tenant under the given operator. */
    public static Tenant partner(String name, UUID operatorId) {
        Objects.requireNonNull(operatorId, "operatorId must not be null for a PARTNER");
        Instant now = Instant.now();
        return new Tenant(UuidGenerator.newV7(), operatorId, Tier.PARTNER, requireName(name), now, now);
    }

    /** Creates a client tenant under the given partner. */
    public static Tenant client(String name, UUID partnerId) {
        Objects.requireNonNull(partnerId, "partnerId must not be null for a CLIENT");
        Instant now = Instant.now();
        return new Tenant(UuidGenerator.newV7(), partnerId, Tier.CLIENT, requireName(name), now, now);
    }

    /**
     * Rebuilds a tenant from already-persisted state. For the persistence layer
     * only: it does not mint a new id or timestamps, and trusts the stored values.
     */
    public static Tenant rehydrate(UUID id, UUID parentTenantId, Tier tier, String name,
                                   Instant createdAt, Instant updatedAt) {
        return new Tenant(
                Objects.requireNonNull(id, "id"),
                parentTenantId,
                Objects.requireNonNull(tier, "tier"),
                Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(createdAt, "createdAt"),
                Objects.requireNonNull(updatedAt, "updatedAt"));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name.strip();
    }

    public UUID id() {
        return id;
    }

    public UUID parentTenantId() {
        return parentTenantId;
    }

    public Tier tier() {
        return tier;
    }

    public String name() {
        return name;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Tenant other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Tenant[id=%s, tier=%s, name=%s, parentTenantId=%s]"
                .formatted(id, tier, name, parentTenantId);
    }
}
