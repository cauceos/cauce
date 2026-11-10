package dev.cauce.channels.webchat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** JPA mapping of {@code web_chat_settings} (V59); RLS-filtered by its config's tenant. */
@Entity
@Table(name = "web_chat_settings")
public class WebChatSettingsEntity {

    @Id
    @Column(name = "channel_config_id", nullable = false, updatable = false)
    private UUID channelConfigId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "allowed_origins", nullable = false, columnDefinition = "text[]")
    private String[] allowedOrigins = new String[0];

    @Column(name = "turnstile_site_key", nullable = false, length = 100)
    private String turnstileSiteKey;

    @Column(name = "privacy_policy_url", nullable = false, length = 500)
    private String privacyPolicyUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_mode", nullable = false, length = 20)
    private ConsentMode consentMode;

    @Column(name = "sessions_per_ip_hour")
    private Integer sessionsPerIpHour;

    @Column(name = "messages_per_session_minute")
    private Integer messagesPerSessionMinute;

    @Column(name = "messages_per_session_day")
    private Integer messagesPerSessionDay;

    @Column(name = "messages_per_ip_minute")
    private Integer messagesPerIpMinute;

    @Column(name = "reads_per_session_minute")
    private Integer readsPerSessionMinute;

    /** The appearance as JSON (V63); null is the platform's neutral look. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "appearance", columnDefinition = "jsonb")
    private String appearance;

    public String getAppearance() {
        return appearance;
    }

    public void setAppearance(String appearance) {
        this.appearance = appearance;
    }

    /** The shop-assistant mode as JSON (V65); null is a chat without it. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "assistant", columnDefinition = "jsonb")
    private String assistant;

    public String getAssistant() {
        return assistant;
    }

    public void setAssistant(String assistant) {
        this.assistant = assistant;
    }

    protected WebChatSettingsEntity() {
        // for JPA
    }

    public WebChatSettingsEntity(UUID channelConfigId, UUID tenantId, String[] allowedOrigins, String turnstileSiteKey,
                                 String privacyPolicyUrl, ConsentMode consentMode) {
        this.channelConfigId = channelConfigId;
        this.tenantId = tenantId;
        this.allowedOrigins = allowedOrigins;
        this.turnstileSiteKey = turnstileSiteKey;
        this.privacyPolicyUrl = privacyPolicyUrl;
        this.consentMode = consentMode;
    }

    public WebChatSettingsEntity(UUID channelConfigId, UUID tenantId, String[] allowedOrigins, String turnstileSiteKey,
                                 String privacyPolicyUrl, ConsentMode consentMode, WebChatLimits limits) {
        this(channelConfigId, tenantId, allowedOrigins, turnstileSiteKey, privacyPolicyUrl, consentMode);
        this.sessionsPerIpHour = limits.sessionsPerIpHour();
        this.messagesPerSessionMinute = limits.messagesPerSessionMinute();
        this.messagesPerSessionDay = limits.messagesPerSessionDay();
        this.messagesPerIpMinute = limits.messagesPerIpMinute();
        this.readsPerSessionMinute = limits.readsPerSessionMinute();
    }

    /** The channel's own limits; null where it keeps the platform's. */
    public WebChatLimits getLimits() {
        return new WebChatLimits(sessionsPerIpHour, messagesPerSessionMinute, messagesPerSessionDay,
                messagesPerIpMinute, readsPerSessionMinute);
    }

    public UUID getChannelConfigId() {
        return channelConfigId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String[] getAllowedOrigins() {
        return allowedOrigins;
    }

    public String getTurnstileSiteKey() {
        return turnstileSiteKey;
    }

    public String getPrivacyPolicyUrl() {
        return privacyPolicyUrl;
    }

    public ConsentMode getConsentMode() {
        return consentMode;
    }
}
