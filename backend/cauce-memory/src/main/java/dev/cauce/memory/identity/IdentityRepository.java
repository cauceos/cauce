package dev.cauce.memory.identity;

import dev.cauce.core.identity.IdentityKind;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link IdentityEntity}. Result sets are filtered by the
 * identities Row-Level Security policy according to the active tenant context.
 */
public interface IdentityRepository extends JpaRepository<IdentityEntity, UUID> {

    /**
     * Finds the identity for the {@code (tenant, channel, kind, value)} key. At most one row
     * exists per key ({@code identities_key} UNIQUE, V25). Used after
     * {@link #insertIfAbsent} to read the winner, and on the hit path of resolve-or-create.
     */
    Optional<IdentityEntity> findByTenantIdAndChannelTypeAndKindAndValue(
            UUID tenantId, String channelType, IdentityKind kind, String value);

    /**
     * Inserts an identity for the {@code (tenant, channel, kind, value)} key unless one already
     * exists, in a single race-free statement. Returns {@code 1} when this call created the
     * row, {@code 0} when a concurrent caller already had; the {@code identities_key} UNIQUE
     * constraint (V25) is the conflict arbiter, exactly as the V13 partial index is for OPEN
     * conversations. The caller then re-reads the row by key to obtain the winner.
     * {@code created_at} defaults to {@code now()}. Runs in the caller's transaction with the
     * tenant RLS context applied, so the insert is subject to the identities
     * {@code WITH CHECK} visibility policy: {@code tenantId} must be visible to the context
     * (the owning tenant itself, or a partner or operator above it).
     *
     * <p>{@code kind} is passed as its enum name: the native statement binds a plain string
     * against the {@code VARCHAR} column, and the database CHECK guards the vocabulary.
     */
    @Modifying
    @Query(value = """
            INSERT INTO identities (id, tenant_id, channel_type, kind, value)
            VALUES (:id, :tenantId, :channelType, :kind, :value)
            ON CONFLICT (tenant_id, channel_type, kind, value) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("tenantId") UUID tenantId,
                       @Param("channelType") String channelType,
                       @Param("kind") String kind,
                       @Param("value") String value);
}
