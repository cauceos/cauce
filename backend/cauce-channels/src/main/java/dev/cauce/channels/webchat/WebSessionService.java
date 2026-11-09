package dev.cauce.channels.webchat;

import dev.cauce.channels.config.ChannelConfig;
import dev.cauce.channels.persistence.ChannelConfigMapper;
import dev.cauce.channels.persistence.ChannelConfigRepository;
import dev.cauce.core.UuidGenerator;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.tenant.NoTenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anonymous web-chat sessions (ADR 0014, W2). A browser opens one with a valid Turnstile token and,
 * when its channel asks for it, the person's acceptance; it gets a 256-bit random token back, once,
 * whose hash is all that is stored. A session lasts 30 days, sliding with each authenticated request,
 * by the database clock. The session's id becomes the value of the person's {@code SESSION_ID}
 * identity with their first message; opening a session creates no identity and no conversation.
 *
 * <p>{@link #open} runs under the channel's tenant context, which the caller establishes from the
 * resolved public key; {@link #authenticate} resolves the session before any context exists.
 */
@Service
public class WebSessionService {

    static final String SESSION_OPENED = "conduct.web.session.opened";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final java.util.regex.Pattern NOTICE_VERSION = java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,32}");

    private final WebSessionRepository sessions;
    private final ChannelConfigRepository configRepository;
    private final ChannelConfigMapper configMapper;
    private final TurnstileVerifier turnstile;
    private final ApiKeyHasher hasher;
    private final AuditEventRecorder auditRecorder;
    private final WebChatProperties properties;
    private final Clock clock;

    public WebSessionService(WebSessionRepository sessions, ChannelConfigRepository configRepository,
                             ChannelConfigMapper configMapper, TurnstileVerifier turnstile, ApiKeyHasher hasher,
                             AuditEventRecorder auditRecorder, WebChatProperties properties, Clock clock) {
        this.sessions = sessions;
        this.configRepository = configRepository;
        this.configMapper = configMapper;
        this.turnstile = turnstile;
        this.hasher = hasher;
        this.auditRecorder = auditRecorder;
        this.properties = properties;
        this.clock = clock;
    }

    /** A session just opened, with its token in clear: returned to the browser once, never again. */
    public record Opened(WebSession session, String token) {
    }

    /**
     * Opens a session on {@code channel}, under its tenant's context.
     *
     * @throws ChannelDisabledException if the channel is switched off
     * @throws ConsentRequiredException if the channel asks for acceptance and {@code consent} is not true,
     *         or the widget did not say which version of its notice text the person accepted
     * @throws TurnstileRejectedException if the Turnstile token does not verify
     * @throws TurnstileUnavailableException if Turnstile cannot be asked
     */
    @Transactional
    public Opened open(ResolvedWebChat channel, String turnstileToken, Boolean consent) {
        return open(channel, turnstileToken, consent, null);
    }

    /**
     * {@link #open(ResolvedWebChat, String, Boolean)} with the version of the widget's notice text the
     * person saw: with acceptance, what was accepted is that text and the privacy link, and the
     * session records a hash of both.
     */
    @Transactional
    public Opened open(ResolvedWebChat channel, String turnstileToken, Boolean consent, String noticeVersion) {
        channel.requireActive();
        boolean acceptance = channel.consentMode() == ConsentMode.ACCEPTANCE;
        if (acceptance && !Boolean.TRUE.equals(consent)) {
            throw new ConsentRequiredException("This channel asks for the privacy notice to be accepted first");
        }
        if (acceptance && (noticeVersion == null || !NOTICE_VERSION.matcher(noticeVersion).matches())) {
            throw new ConsentRequiredException("An accepted notice needs the notice_version of the widget's text "
                    + "(1-32 letters, digits, '.', '_' or '-')");
        }
        ChannelConfig config = configRepository.findById(channel.channelConfigId()).map(configMapper::toDomain)
                .orElseThrow(() -> new TurnstileRejectedException("No such channel"));
        if (!turnstile.verify(config.credential(), turnstileToken)) {
            throw new TurnstileRejectedException("The Turnstile token did not verify");
        }
        String token = newToken();
        var now = clock.instant();
        WebSessionEntity entity = new WebSessionEntity(UuidGenerator.newV7(), channel.tenantId(),
                channel.channelConfigId(), hasher.hash(token), now.plus(Duration.ofDays(properties.getSessionDays())),
                acceptance ? now : null, acceptance ? noticeHash(noticeVersion, channel.privacyPolicyUrl()) : null);
        WebSession session = sessions.save(entity).toDomain();
        auditRecorder.record(new AuditEvent(channel.tenantId(), SESSION_OPENED, Map.of(
                "session_id", session.id().toString(),
                "channel_config_id", channel.channelConfigId().toString(),
                "consent_recorded", acceptance)));
        return new Opened(session, token);
    }

    /**
     * The unexpired session {@code token} authenticates, if it belongs to {@code channel}: resolved
     * before any tenant context exists. A token of another channel, expired, unknown or malformed is
     * empty, all alike. The caller then slides it under the session's tenant context ({@link #slide}).
     */
    @Transactional(readOnly = true)
    @NoTenantContext
    public Optional<WebSession> authenticate(ResolvedWebChat channel, String token) {
        if (token == null || token.isBlank() || token.length() > 128) {
            return Optional.empty();
        }
        return sessions.resolveByTokenHash(hasher.hash(token))
                .filter(row -> row.getChannel_config_id().equals(channel.channelConfigId()))
                .map(row -> new WebSession(row.getId(), row.getTenant_id(), row.getChannel_config_id(),
                        row.getExpires_at(), null));
    }

    /**
     * Moves the session's expiry {@code sessionDays} ahead of the database's now, under its tenant's
     * context: each authenticated request keeps an active person's session alive (ADR 0014, WC2).
     * Returns whether it was still there to slide.
     */
    @Transactional
    public boolean slide(UUID sessionId) {
        return sessions.slide(sessionId, properties.getSessionDays()) == 1;
    }

    /** Deletes the expired sessions of every tenant; returns how many. */
    @Transactional
    @NoTenantContext
    public int purgeExpired() {
        return sessions.purgeExpired();
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * A hash of what the person accepted (ADR 0014, amendment): the version of the widget's notice text
     * and the privacy link, so a later change of either is a different notice.
     */
    static String noticeHash(String noticeVersion, String privacyPolicyUrl) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(("notice:" + noticeVersion + "\nprivacy:"
                    + privacyPolicyUrl).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
