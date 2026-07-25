package dev.cauce.tenancy.audit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cauce.core.agent.Agent;
import dev.cauce.core.apikey.ApiKey;
import dev.cauce.core.apikey.ApiKeyHasher;
import dev.cauce.core.audit.AuditContentHash;
import dev.cauce.core.audit.AuditEvent;
import dev.cauce.core.tenant.Tenant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminAuditEventsTest {

    private static final ApiKeyHasher FAKE_HASHER = new ApiKeyHasher() {
        @Override public String hash(String plaintext) { return "h:" + plaintext; }
        @Override public boolean matches(String p, String h) { return h.equals("h:" + p); }
    };

    private final UUID actorTenantId = UUID.randomUUID();

    @Test
    void tenantCreated_buildsSubjectChainPayloadWithoutBusinessName() {
        Tenant operator = Tenant.operator("Op");
        Tenant partner = Tenant.partner("Partner Co", operator.id());

        AuditEvent event = AdminAuditEvents.tenantCreated(partner, actorTenantId);

        assertThat(event.tenantId()).isEqualTo(partner.id()); // the SUBJECT's chain
        assertThat(event.eventType()).isEqualTo("admin.tenant.created");
        assertThat(event.payload())
                .containsOnlyKeys("tenant_id", "tier", "parent_tenant_id", "actor_tenant_id")
                .containsEntry("tier", "PARTNER")
                .containsEntry("parent_tenant_id", operator.id().toString())
                .containsEntry("actor_tenant_id", actorTenantId.toString());
        assertThat(event.payload().values()).doesNotContain("Partner Co");
    }

    @Test
    void agentCreated_buildsPayloadWithPromptHashNeverRawPrompt() {
        UUID tenantId = UUID.randomUUID();
        Agent agent = Agent.create(tenantId, "Bot", "Secret playbook.", "anthropic",
                "claude-sonnet-4-7");

        AuditEvent event = AdminAuditEvents.agentCreated(agent, actorTenantId);

        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.eventType()).isEqualTo("admin.agent.created");
        assertThat(event.payload())
                .containsEntry("prompt_hash", AuditContentHash.of(tenantId, "Secret playbook."))
                .containsEntry("prompt_length", 16)
                .containsEntry("model_provider", "anthropic")
                .containsEntry("actor_tenant_id", actorTenantId.toString());
        assertThat(event.payload().values()).doesNotContain("Secret playbook.", "Bot");
    }

    @Test
    void apiKeyIssued_buildsPayloadWithoutPlaintextOrHash() {
        UUID tenantId = UUID.randomUUID();
        String plaintext = "ck_1234567890abcdefghijklmnopqrstuv";
        ApiKey apiKey = ApiKey.create(tenantId, "Production", plaintext, FAKE_HASHER);

        AuditEvent event = AdminAuditEvents.apiKeyIssued(apiKey, actorTenantId);

        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.eventType()).isEqualTo("admin.apikey.issued");
        assertThat(event.payload())
                .containsOnlyKeys("api_key_id", "tenant_id", "key_prefix", "actor_tenant_id")
                .containsEntry("api_key_id", apiKey.id().toString())
                .containsEntry("key_prefix", apiKey.keyPrefix());
        assertThat(event.payload().values())
                .doesNotContain(plaintext, apiKey.keyHash(), "Production");
    }

    @Test
    void apiKeyRevoked_buildsMinimalPayload() {
        UUID tenantId = UUID.randomUUID();
        ApiKey apiKey = ApiKey.create(tenantId, "k", "ck_1234567890abcdefghijklmnopqrstuv",
                FAKE_HASHER);

        AuditEvent event = AdminAuditEvents.apiKeyRevoked(apiKey, actorTenantId);

        assertThat(event.eventType()).isEqualTo("admin.apikey.revoked");
        assertThat(event.payload())
                .containsOnlyKeys("api_key_id", "tenant_id", "actor_tenant_id");
    }

    @Test
    void reservedHookTypes_areDefinedButDistinctFromEmittedTypes() {
        // agent.updated has no modification operation yet; credential.changed has no
        // per-tenant credential path. Reserved vocabulary, zero emission until a trigger
        // exists — the admin IT asserts the chain contains only emitted types.
        assertThat(Set.of(AdminAuditEvents.AGENT_UPDATED, AdminAuditEvents.CREDENTIAL_CHANGED))
                .hasSize(2)
                .allSatisfy(type -> assertThat(type).startsWith("admin."))
                .doesNotContain(AdminAuditEvents.TENANT_CREATED, AdminAuditEvents.AGENT_CREATED,
                        AdminAuditEvents.APIKEY_ISSUED, AdminAuditEvents.APIKEY_REVOKED);
    }
}
