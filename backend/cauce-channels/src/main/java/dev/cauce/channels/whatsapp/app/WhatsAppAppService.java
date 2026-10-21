package dev.cauce.channels.whatsapp.app;

import dev.cauce.channels.audit.ChannelAuditEvents;
import dev.cauce.core.apikey.ApiKeyGenerator;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.audit.AuditEventRecorder;
import dev.cauce.core.tenant.NoTenantContext;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.core.tenant.TenantNotFoundException;
import dev.cauce.memory.tenant.TenantRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for WhatsApp apps (ADR 0006, W2). Tenant-scoped like every other service:
 * {@code RlsContextAspect} sets the RLS context from {@code TenantContext}, so registering for a
 * tenant outside the caller's scope is a not-found, and an app is visible exactly to its tenant
 * and the tenants above it.
 */
@Service
public class WhatsAppAppService {

    private final WhatsAppAppRepository repository;
    private final WhatsAppAppMapper mapper;
    private final TenantRepository tenantRepository;
    private final ApiKeyHasher hasher;
    private final AuditEventRecorder auditRecorder;

    public WhatsAppAppService(WhatsAppAppRepository repository, WhatsAppAppMapper mapper,
                              TenantRepository tenantRepository, ApiKeyHasher hasher,
                              AuditEventRecorder auditRecorder) {
        this.repository = repository;
        this.mapper = mapper;
        this.tenantRepository = tenantRepository;
        this.hasher = hasher;
        this.auditRecorder = auditRecorder;
    }

    /**
     * Registers the Meta app {@code metaAppId} for {@code tenantId}, storing {@code appSecret}
     * sealed and minting the webhook verify token, which is returned once and stored only hashed.
     * Recorded as {@code admin.whatsapp_app.registered} in the owning tenant's chain, with ids
     * only — never the secret, the token or its hash.
     *
     * @throws TenantNotFoundException if the tenant is not visible under the current context
     */
    @Transactional
    public WhatsAppAppRegistration registerApp(UUID tenantId, String metaAppId, String appSecret) {
        tenantRepository.findById(tenantId).orElseThrow(() ->
                new TenantNotFoundException("No tenant found for id " + tenantId));
        String verifyToken = ApiKeyGenerator.newKey();
        WhatsAppApp app = WhatsAppApp.create(tenantId, metaAppId, appSecret, hasher.hash(verifyToken));
        WhatsAppApp saved = mapper.toDomain(repository.save(mapper.toEntity(app)));
        auditRecorder.record(ChannelAuditEvents.whatsAppAppRegistered(
                saved, TenantContext.getCurrentTenantId().orElseThrow()));
        return new WhatsAppAppRegistration(saved, verifyToken);
    }

    /** The app, if visible under the current tenant context (RLS). */
    @Transactional(readOnly = true)
    public Optional<WhatsAppApp> getApp(UUID appId) {
        return repository.findById(appId).map(mapper::toDomain);
    }

    /**
     * The ACTIVE app {@code appId} for the webhook path, regardless of tenant context — the app is
     * what the URL names. {@link NoTenantContext}: the lookup goes through the V30
     * {@code SECURITY DEFINER} function (ADR 0001); nothing is trusted until the signature verifies.
     */
    @Transactional(readOnly = true)
    @NoTenantContext
    public Optional<WhatsAppApp> resolveActiveApp(UUID appId) {
        return repository.resolveActiveApp(appId).map(mapper::toDomain);
    }
}
