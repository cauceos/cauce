package dev.cauce.tenancy.audit;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.apikey.ApiKey;
import dev.cauce.core.audit.AuditContentHash;
import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.tenant.Tenant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The Family-B auditable vocabulary owned by tenancy: administration of tenants, agents,
 * and API keys — who changed the rules and the authority. Each factory builds the payload
 * of one emitted event: NON-SENSITIVE metadata, the affected resource by id, and the ACTOR
 * (`actor_tenant_id` — the acting tenant from the validated API key, which ADR 0002 defines
 * as THE authority identity; per-key actor attribution is deferred with fine-grained
 * authorization). Events land in the chain of the SUBJECT tenant (the tenant whose
 * rules/existence are the fact), so "tenant X's chain = everything that happened to X";
 * hierarchical visibility lets the actor read it without duplicating the event.
 *
 * <p>Never in a payload: an API key's plaintext or HMAC hash (only {@code key_id} +
 * {@code key_prefix}, the public listing metadata), a tenant's business name, or the
 * agent's raw system prompt — the prompt is sensitive, mutable business logic and enters
 * only as {@code prompt_hash} ({@link AuditContentHash}), binding the exact configuration
 * without storing it.
 *
 * <p><b>Emitted</b> — each inside the transaction of the admin operation it audits:
 * {@link #TENANT_CREATED} (createPartner/createClient; the operator bootstrap is the
 * documented exception — it runs without a transaction, tenant context, or actor, on the
 * privileged genesis path), {@link #AGENT_CREATED}, {@link #APIKEY_ISSUED},
 * {@link #APIKEY_REVOKED}. {@code admin.channel.configured} is emitted by cauce-channels
 * (its constant lives with that emitter).
 *
 * <p><b>Reserved hooks</b> — type defined now, no emission because no trigger exists:
 * {@link #AGENT_UPDATED} (there is no agent-modification operation yet) and
 * {@link #CREDENTIAL_CHANGED} (no per-tenant LLM credential path exists — only the
 * system-default env credential).
 */
public final class AdminAuditEvents {

    // Emitted types.
    public static final String TENANT_CREATED = "admin.tenant.created";
    public static final String AGENT_CREATED = "admin.agent.created";
    public static final String APIKEY_ISSUED = "admin.apikey.issued";
    public static final String APIKEY_REVOKED = "admin.apikey.revoked";

    // Reserved hook types — defined, never emitted until a real trigger exists.
    public static final String AGENT_UPDATED = "admin.agent.updated";
    public static final String CREDENTIAL_CHANGED = "admin.credential.changed";

    private AdminAuditEvents() {
    }

    /** A partner or client tenant was created — recorded in the NEW tenant's own chain. */
    public static AuditEvent tenantCreated(Tenant tenant, UUID actorTenantId) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(actorTenantId, "actorTenantId must not be null");
        return new AuditEvent(tenant.id(), TENANT_CREATED, Map.of(
                "tenant_id", tenant.id().toString(),
                "tier", tenant.tier().name(),
                "parent_tenant_id", tenant.parentTenantId().toString(),
                "actor_tenant_id", actorTenantId.toString()));
    }

    /** An agent was created under its owning tenant — the prompt enters only as a hash. */
    public static AuditEvent agentCreated(Agent agent, UUID actorTenantId) {
        Objects.requireNonNull(agent, "agent must not be null");
        Objects.requireNonNull(actorTenantId, "actorTenantId must not be null");
        return new AuditEvent(agent.tenantId(), AGENT_CREATED, Map.of(
                "agent_id", agent.id().toString(),
                "tenant_id", agent.tenantId().toString(),
                "model_provider", agent.modelProvider(),
                "model_name", agent.modelName(),
                "prompt_hash", AuditContentHash.of(agent.tenantId(), agent.systemPrompt()),
                "prompt_length", agent.systemPrompt().length(),
                "actor_tenant_id", actorTenantId.toString()));
    }

    /**
     * An API key was minted for its owning tenant. Only the id and the public prefix are
     * recorded — NEVER the plaintext (which exists once, in the creation result) nor the
     * stored HMAC hash.
     */
    public static AuditEvent apiKeyIssued(ApiKey apiKey, UUID actorTenantId) {
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        Objects.requireNonNull(actorTenantId, "actorTenantId must not be null");
        return new AuditEvent(apiKey.tenantId(), APIKEY_ISSUED, Map.of(
                "api_key_id", apiKey.id().toString(),
                "tenant_id", apiKey.tenantId().toString(),
                "key_prefix", apiKey.keyPrefix(),
                "actor_tenant_id", actorTenantId.toString()));
    }

    /** An API key was revoked (one-way transition). */
    public static AuditEvent apiKeyRevoked(ApiKey apiKey, UUID actorTenantId) {
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        Objects.requireNonNull(actorTenantId, "actorTenantId must not be null");
        return new AuditEvent(apiKey.tenantId(), APIKEY_REVOKED, Map.of(
                "api_key_id", apiKey.id().toString(),
                "tenant_id", apiKey.tenantId().toString(),
                "actor_tenant_id", actorTenantId.toString()));
    }
}
