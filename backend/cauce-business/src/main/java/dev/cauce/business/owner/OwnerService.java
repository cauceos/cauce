package dev.cauce.business.owner;

import dev.cauce.business.BusinessTenants;
import dev.cauce.business.audit.BusinessAuditEvents;
import dev.cauce.business.profile.BusinessProfileEntity;
import dev.cauce.business.profile.BusinessProfileRepository;
import dev.cauce.core.UuidGenerator;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.identity.Identity;
import dev.cauce.core.inbound.InboundMessageInterceptor;
import dev.cauce.tenancy.IdentityService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner of a business (ADR 0007, B8): issues single-use link codes for the owner bot, binds the
 * owner's chat when a code arrives on that bot, and says where the owner reads.
 *
 * <p>As an {@link InboundMessageInterceptor} it looks at every inbound text, but only one shaped
 * like a code ({@code CODE} or Telegram's {@code /start CODE}) reaches the database. A code binds
 * only on the bot it was issued for, under that bot's own tenant. A code used again by the same
 * chat is answered again and changes nothing (a webhook redelivery); expired, or used by someone
 * else, it is answered as not valid. A text that matches no code goes on to the agent.
 */
@Service
public class OwnerService implements InboundMessageInterceptor {

    /** How long an issued code can be used. */
    public static final Duration CODE_VALIDITY = Duration.ofHours(24);
    static final int CODE_LENGTH = 10;

    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final Pattern CODE_TEXT = Pattern.compile("^(?:/START\\s+)?([A-Z2-9]{" + CODE_LENGTH + "})$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OwnerLinkCodeRepository codeRepository;
    private final OwnerRecipientRepository recipientRepository;
    private final BusinessProfileRepository profileRepository;
    private final BusinessTenants businessTenants;
    private final IdentityService identityService;
    private final AuditEventRecorder auditRecorder;
    private final Clock clock;

    public OwnerService(OwnerLinkCodeRepository codeRepository, OwnerRecipientRepository recipientRepository,
                        BusinessProfileRepository profileRepository, BusinessTenants businessTenants,
                        IdentityService identityService, AuditEventRecorder auditRecorder,
                        @Qualifier("businessClock") Clock clock) {
        this.codeRepository = codeRepository;
        this.recipientRepository = recipientRepository;
        this.profileRepository = profileRepository;
        this.businessTenants = businessTenants;
        this.identityService = identityService;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    /** A code issued for the owner bot: the plaintext exists only here, once. */
    public record IssuedCode(String code, UUID channelConfigId, Instant expiresAt) {
    }

    /**
     * Issues a link code for the owner bot {@code channelConfigId} of the business {@code tenantId}.
     * The bot must be an active channel config of that same business; the code is stored hashed and
     * expires after {@link #CODE_VALIDITY}.
     *
     * @throws dev.cauce.core.tenant.TenantNotFoundException if the tenant is not visible
     * @throws dev.cauce.core.tenant.InvalidTenantTierException if it is not a business
     * @throws IllegalArgumentException if the channel config is not an active one of the business
     */
    @Transactional
    public IssuedCode issueLinkCode(UUID tenantId, UUID channelConfigId) {
        businessTenants.requireBusiness(tenantId);
        UUID configTenant = recipientRepository.findActiveChannelConfigTenant(channelConfigId).orElse(null);
        if (!tenantId.equals(configTenant)) {
            throw new IllegalArgumentException("channel config " + channelConfigId
                    + " is not an active channel of business " + tenantId);
        }
        String code = newCode();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = now.plus(CODE_VALIDITY);
        codeRepository.save(new OwnerLinkCodeEntity(UuidGenerator.newV7(), tenantId, channelConfigId, hash(code),
                expiresAt, now));
        return new IssuedCode(code, channelConfigId, expiresAt);
    }

    /** The owner of the business {@code tenantId}, if one is linked and visible. */
    @Transactional(readOnly = true)
    public Optional<OwnerRecipient> findOwner(UUID tenantId) {
        return recipientRepository.findByTenantId(tenantId).map(OwnerService::toDomain);
    }

    @Override
    @Transactional
    public Optional<String> intercept(InboundText text) {
        Matcher matcher = CODE_TEXT.matcher(text.content().strip().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Optional<OwnerLinkCodeEntity> found = codeRepository.findByCodeHash(hash(matcher.group(1)))
                .filter(code -> code.getChannelConfigId().equals(text.channelConfigId())
                        && code.getTenantId().equals(text.tenantId()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        OwnerLinkCodeEntity code = found.get();
        String locale = profileRepository.findByTenantId(code.getTenantId()).map(BusinessProfileEntity::getLocale)
                .orElse(null);
        Identity identity = identityService.resolveOrCreate(code.getTenantId(), text.channelType(),
                text.identityKind(), text.identityValue());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (code.getUsedAt() != null) {
            return Optional.of(identity.id().equals(code.getUsedByIdentityId())
                    ? OwnerMessages.linked(locale, businessName(code.getTenantId()))
                    : OwnerMessages.codeNotValid(locale));
        }
        if (!code.getExpiresAt().isAfter(now)) {
            return Optional.of(OwnerMessages.codeNotValid(locale));
        }
        code.markUsed(identity.id(), now);
        codeRepository.save(code);
        OwnerRecipientEntity recipient = recipientRepository.findByTenantId(code.getTenantId())
                .map(existing -> new OwnerRecipientEntity(existing.getId(), existing.getTenantId(),
                        code.getChannelConfigId(), identity.id(), now))
                .orElseGet(() -> new OwnerRecipientEntity(UuidGenerator.newV7(), code.getTenantId(),
                        code.getChannelConfigId(), identity.id(), now));
        recipientRepository.save(recipient);
        auditRecorder.record(BusinessAuditEvents.ownerLinked(code.getTenantId(), recipient.getId(),
                code.getChannelConfigId(), identity.id()));
        return Optional.of(OwnerMessages.linked(locale, businessName(code.getTenantId())));
    }

    private String businessName(UUID tenantId) {
        return profileRepository.findByTenantId(tenantId).map(BusinessProfileEntity::getName).orElse("Cauce");
    }

    private static OwnerRecipient toDomain(OwnerRecipientEntity entity) {
        return new OwnerRecipient(entity.getId(), entity.getTenantId(), entity.getChannelConfigId(),
                entity.getIdentityId(), entity.getLinkedAt());
    }

    private static String newCode() {
        char[] code = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            code[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }

    /** SHA-256 of the code: a link code is only ever compared, so only its hash is stored. */
    static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
