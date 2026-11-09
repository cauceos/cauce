package dev.cauce.channels.webchat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@code web_sessions}, RLS-filtered, except the two V60
 * {@code SECURITY DEFINER} functions: resolving a session by its token's hash before any tenant context
 * exists, and purging the expired ones across tenants (ADR 0001).
 */
public interface WebSessionRepository extends JpaRepository<WebSessionEntity, UUID> {

    /** The unexpired session whose token hashes to {@code tokenHash}, regardless of tenant context. */
    @Query(value = "SELECT * FROM resolve_web_session(:tokenHash)", nativeQuery = true)
    Optional<Resolved> resolveByTokenHash(@Param("tokenHash") String tokenHash);

    /** A row of {@code resolve_web_session}. */
    interface Resolved {
        UUID getId();

        UUID getTenant_id();

        UUID getChannel_config_id();

        Instant getExpires_at();
    }

    /**
     * Moves the session's expiry to {@code now() + 30 days} and stamps it seen, by the database clock
     * (ADR 0008), under RLS; returns the new expiry, or nothing when the session is not visible.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE web_sessions SET last_seen_at = now(), expires_at = now() + make_interval(days => :days) "
            + "WHERE id = :id AND expires_at > now()", nativeQuery = true)
    int slide(@Param("id") UUID id, @Param("days") int days);

    /** Deletes every expired session, across tenants; returns how many. */
    @Query(value = "SELECT purge_expired_web_sessions()", nativeQuery = true)
    int purgeExpired();
}
