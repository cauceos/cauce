package dev.cauce.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.agent.CreateAgentRequest;
import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.api.tenant.CreateClientRequest;
import dev.cauce.api.tenant.CreatePartnerRequest;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.governance.audit.AuditDrainerProperties;
import dev.cauce.governance.audit.AuditOutboxDrainService;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end test of the audit chain verification surface. Audit entries are generated the
 * real way — administration events (Family B) emitted when the hierarchy is created through
 * the API — then drained into the ledger and verified through
 * {@code GET /v1/tenants/{tenantId}/audit/chain-verification}. Real database, real RLS via the
 * {@code cauce_app} role, real drainer and verifier. The privileged manipulation and pre-chain
 * rows use the owner connection, exactly as the governance chain unit does.
 */
class ChainVerificationApiIT extends AbstractApiIntegrationTest {

    /** The golden-rule regression: no compliance vocabulary may ever reach the wire. */
    private static final List<String> FORBIDDEN = List.of(
            "compliant", "gdpr", "legal", "court", "admissible",
            "certified", "guaranteed", "tamper-proof", "protected");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuditOutboxDrainService drainService;

    @Autowired
    private AuditDrainerProperties drainerProperties;

    private JdbcTemplate jdbc;

    private UUID operatorId;
    private String operatorAuth;
    private UUID partnerId;
    private String partnerAuth;
    private UUID clientAId;
    private String clientAAuth;

    @BeforeEach
    void setUp() throws Exception {
        truncateAll();
        TenantContext.clear();
        jdbc = new JdbcTemplate(adminDataSource);

        operatorId = tenantService.bootstrapOperator("Operator").id();
        operatorAuth = bearerFor(operatorId);
        partnerId = createPartner();
        partnerAuth = bearerFor(partnerId);
        clientAId = createClient(partnerAuth, partnerId);
        clientAAuth = bearerFor(clientAId);
        // An extra administration event on clientA's chain, so it holds several entries.
        createAgent(clientAAuth, clientAId);
        // Left UNDRAINED on purpose: each test drains exactly what it needs.
    }

    // === VALID CHAIN ===

    @Test
    void getChainVerification_intactChain_returns200Valid() throws Exception {
        int drained = drainAll(clientAId);
        assertThat(drained).isPositive();

        mockMvc.perform(getAs(clientAAuth, chainUrl(clientAId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant_id").value(clientAId.toString()))
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.verified_entries").value(drained))
                .andExpect(jsonPath("$.pre_chain_entries").value(0))
                .andExpect(jsonPath("$.first_break").value((Object) null))
                .andExpect(jsonPath("$.verification_scope.method").exists())
                .andExpect(jsonPath("$.verification_scope.does_not_detect").exists())
                .andExpect(jsonPath("$.verified_at").exists());
    }

    @Test
    void getChainVerification_tenantWithNoDrainedEvents_returnsValidEmpty() throws Exception {
        // clientA's events sit in the outbox, undrained: its ledger chain is empty.
        mockMvc.perform(getAs(clientAAuth, chainUrl(clientAId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.verified_entries").value(0))
                .andExpect(jsonPath("$.pre_chain_entries").value(0))
                .andExpect(jsonPath("$.first_break").value((Object) null));
    }

    // === BROKEN CHAIN ===

    @Test
    void getChainVerification_ownerTamperedRow_returnsBrokenWithExactSequenceAndClassification()
            throws Exception {
        drainAll(clientAId);
        // The owner connection bypasses the V21 append-only revoke — the privileged attacker.
        tamperPayload(clientAId, 2);

        mockMvc.perform(getAs(clientAAuth, chainUrl(clientAId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BROKEN"))
                .andExpect(jsonPath("$.first_break.sequence_number").value(2))
                .andExpect(jsonPath("$.first_break.classification").value("ENTRY_ALTERED"));
    }

    // === PRE-CHAIN PREFIX (honestly reported, never backfilled) ===

    @Test
    void getChainVerification_preChainPrefix_reportsPreChainCount() throws Exception {
        // Two owner-inserted rows with NULL hashes precede the chain (a foreign deployment's
        // pre-chain legacy); the real drain then chains from sequence 3 at genesis.
        insertPreChainRow(clientAId, 1);
        insertPreChainRow(clientAId, 2);
        int drained = drainAll(clientAId);
        assertThat(drained).isPositive();

        mockMvc.perform(getAs(clientAAuth, chainUrl(clientAId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.pre_chain_entries").value(2))
                .andExpect(jsonPath("$.verified_entries").value(drained));
    }

    // === ISOLATION (the security test of the unit) ===

    @Test
    void getChainVerification_byNonVisibleTenant_returns404TenantNotFound() throws Exception {
        drainAll(clientAId);

        // A sibling partner's client has no hierarchical path to clientA's chain: 404,
        // indistinguishable from a nonexistent tenant (no "200 valid empty" leak).
        UUID partnerB = createPartner();
        UUID clientB = createClient(bearerFor(partnerB), partnerB);
        mockMvc.perform(getAs(bearerFor(clientB), chainUrl(clientAId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("tenant_not_found"));

        // The partner above clientA CAN verify it (hierarchical RLS visibility)...
        mockMvc.perform(getAs(partnerAuth, chainUrl(clientAId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID"));
        // ...and so can the operator at the top of the hierarchy.
        mockMvc.perform(getAs(operatorAuth, chainUrl(clientAId)))
                .andExpect(status().isOk());
    }

    // === VOCABULARY (golden-rule regression, over the SERIALIZED JSON, valid AND broken) ===

    @Test
    void chainVerificationResponses_neverContainComplianceVocabulary() throws Exception {
        drainAll(clientAId);
        String validBody = getAs(clientAAuth, chainUrl(clientAId), status().isOk());

        tamperPayload(clientAId, 2);
        String brokenBody = getAs(clientAAuth, chainUrl(clientAId), status().isOk());

        for (String body : List.of(validBody, brokenBody)) {
            String lower = body.toLowerCase(Locale.ROOT);
            for (String term : FORBIDDEN) {
                assertThat(lower)
                        .as("response JSON must not contain the term '%s'", term)
                        .doesNotContain(term);
            }
        }
        // Sanity: we actually scanned the scope text and both statuses.
        assertThat(validBody).contains("does_not_detect").contains("\"VALID\"");
        assertThat(brokenBody).contains("\"BROKEN\"");
    }

    // --- helpers ---

    private static String chainUrl(UUID tenantId) {
        return "/v1/tenants/" + tenantId + "/audit/chain-verification";
    }

    /** Drains all of a tenant's PENDING outbox rows into the chain, under its context. */
    private int drainAll(UUID tenantId) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            int total = 0;
            int batch;
            do {
                batch = drainService.drainBatch(tenantId, drainerProperties.getBatchSize());
                total += batch;
            } while (batch > 0);
            return total;
        } finally {
            TenantContext.clear();
        }
    }

    private void tamperPayload(UUID tenantId, long sequence) {
        int updated = jdbc.update("UPDATE audit_log_entries "
                + "SET payload = '{\"tampered\": true}'::jsonb "
                + "WHERE tenant_id = ? AND sequence_number = ?", tenantId, sequence);
        assertThat(updated).isEqualTo(1);
    }

    private void insertPreChainRow(UUID tenantId, long sequence) {
        jdbc.update("INSERT INTO audit_log_entries "
                        + "(id, tenant_id, sequence_number, outbox_id, event_type, payload, "
                        + "drained_at) VALUES (?, ?, ?, ?, ?, '{\"legacy\": true}'::jsonb, now())",
                UUID.randomUUID(), tenantId, sequence, UUID.randomUUID(), "legacy.event");
    }

    private String bearerFor(UUID tenantId) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            return "Bearer " + apiKeyService.createApiKey(tenantId, "it-key").plaintextKey();
        } finally {
            TenantContext.clear();
        }
    }

    private MockHttpServletRequestBuilder getAs(String auth, String path) {
        return get(path).header(HttpHeaders.AUTHORIZATION, auth);
    }

    private String getAs(String auth, String path,
                         org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        return mockMvc.perform(getAs(auth, path))
                .andExpect(expected)
                .andReturn().getResponse().getContentAsString();
    }

    private UUID createPartner() throws Exception {
        String body = mockMvc.perform(postAs(operatorAuth, "/v1/tenants/partner",
                        new CreatePartnerRequest("Partner", operatorId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private UUID createClient(String auth, UUID parentPartnerId) throws Exception {
        String body = mockMvc.perform(postAs(auth, "/v1/tenants/client",
                        new CreateClientRequest("Client", parentPartnerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private void createAgent(String auth, UUID tenantId) throws Exception {
        mockMvc.perform(postAs(auth, "/v1/tenants/" + tenantId + "/agents",
                        new CreateAgentRequest("Bot", "You are helpful", "anthropic",
                                "claude-sonnet-4-7", null, null)))
                .andExpect(status().isCreated());
    }

    private MockHttpServletRequestBuilder postAs(String auth, String path, Object body)
            throws Exception {
        return post(path)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }
}
