package dev.cauce.channels.webchat;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@code web_chat_settings}, RLS-filtered. {@link #resolveByPublicKey} is
 * the exception: the public surface resolves a channel by its key before any tenant context exists,
 * through the V59 {@code SECURITY DEFINER} function (ADR 0001).
 */
public interface WebChatSettingsRepository extends JpaRepository<WebChatSettingsEntity, UUID> {

    /** The ACTIVE web chat whose public key is {@code publicKey}, regardless of tenant context. */
    @Query(value = "SELECT * FROM resolve_web_chat_channel(:publicKey)", nativeQuery = true)
    Optional<ResolvedRow> resolveByPublicKey(@Param("publicKey") String publicKey);

    /** A row of {@code resolve_web_chat_channel}; the origins come space-separated. */
    interface ResolvedRow {
        UUID getChannel_config_id();

        UUID getTenant_id();

        UUID getAgent_id();

        String getPublic_key();

        String getAllowed_origins();

        String getTurnstile_site_key();

        String getPrivacy_policy_url();

        String getConsent_mode();

        String getStatus();

        Integer getSessions_per_ip_hour();

        Integer getMessages_per_session_minute();

        Integer getMessages_per_session_day();

        Integer getMessages_per_ip_minute();

        Integer getReads_per_session_minute();
    }

    /** Whether {@code tenantId} has an ACTIVE web chat, under RLS. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM channel_configs c JOIN web_chat_settings s "
            + "ON s.channel_config_id = c.id WHERE c.tenant_id = :tenantId AND c.status = 'ACTIVE')",
            nativeQuery = true)
    boolean hasActiveChannel(@Param("tenantId") UUID tenantId);
}
