package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cauce.api.agent.CreateAgentRequest;
import dev.cauce.api.message.PostMessageRequest;
import dev.cauce.api.support.AbstractApiIntegrationTest;
import dev.cauce.api.tenant.CreateClientRequest;
import dev.cauce.api.tenant.CreatePartnerRequest;
import dev.cauce.core.tenant.TenantContext;
import dev.cauce.tenancy.ApiKeyService;
import dev.cauce.tenancy.TenantService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end tests of the uniform keyset-pagination contract on the three list endpoints
 * ({@code {data, next_cursor}}, ordered by UUIDv7 id). The critical behaviours: a full walk
 * yields every element exactly once, in strictly ascending id order; rows inserted while
 * paginating (the live-conversation case) appear at the end without disturbing the walk;
 * and RLS is enforced on every page. The worker is disabled so posted USER messages
 * accumulate deterministically.
 */
@TestPropertySource(properties = {
        "cauce.orchestration.worker.enabled=false",
        "cauce.orchestration.worker.reaper.enabled=false"
})
class PaginationApiIT extends AbstractApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID operatorId;
    private String operatorAuth;
    private UUID partnerId;
    private String partnerAuth;
    private UUID clientId;
    private String clientAuth;
    private UUID agentId;

    @BeforeEach
    void setUp() throws Exception {
        truncateAll();
        TenantContext.clear();

        operatorId = tenantService.bootstrapOperator("Operator").id();
        operatorAuth = bearerFor(operatorId);
        partnerId = createPartner();
        partnerAuth = bearerFor(partnerId);
        clientId = createClient(partnerAuth, partnerId);
        clientAuth = bearerFor(clientId);
        agentId = createAgent(clientAuth, clientId);
    }

    @Test
    void listMessages_fullWalkWithSmallLimit_returnsAllWithoutSkipsOrDuplicates() throws Exception {
        UUID conversationId = null;
        for (int i = 0; i < 5; i++) {
            conversationId = postMessage("user-1", "msg-" + i);
        }

        List<String> walked = walk("/v1/conversations/" + conversationId + "/messages", clientAuth, 2);

        assertThat(walked).hasSize(5);
        assertThat(new HashSet<>(walked)).as("no duplicates").hasSize(5);
        assertThat(walked).as("strictly ascending id order (UUIDv7 canonical order)").isSorted();
    }

    @Test
    void listMessages_insertBetweenPages_appendsWithoutDisturbingTheWalk() throws Exception {
        UUID conversationId = null;
        for (int i = 0; i < 4; i++) {
            conversationId = postMessage("user-1", "msg-" + i);
        }

        // Page 1 (limit 2).
        JsonNode page1 = getPage("/v1/conversations/" + conversationId + "/messages",
                clientAuth, 2, null);
        List<String> seen = new ArrayList<>(idsOf(page1));
        String cursor = page1.get("next_cursor").asText();
        assertThat(seen).hasSize(2);

        // A new message lands mid-walk — the live-conversation case.
        postMessage("user-1", "mid-walk");

        // Finish the walk from the same cursor.
        while (cursor != null) {
            JsonNode page = getPage("/v1/conversations/" + conversationId + "/messages",
                    clientAuth, 2, cursor);
            seen.addAll(idsOf(page));
            cursor = page.get("next_cursor").isNull() ? null : page.get("next_cursor").asText();
        }

        // All 4 original messages plus the mid-walk one: nothing skipped, nothing repeated,
        // and the new row (a later UUIDv7) shows up at the end of the traversal.
        assertThat(seen).hasSize(5);
        assertThat(new HashSet<>(seen)).hasSize(5);
        assertThat(seen).isSorted();
    }

    @Test
    void listMessages_limitAboveMax_isClampedAndSucceeds() throws Exception {
        UUID conversationId = postMessage("user-1", "solo");

        mockMvc.perform(getAs(clientAuth,
                        "/v1/conversations/" + conversationId + "/messages").param("limit", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.next_cursor").value((Object) null));
    }

    @Test
    void listMessages_invalidCursor_returns400InvalidCursor() throws Exception {
        UUID conversationId = postMessage("user-1", "solo");

        mockMvc.perform(getAs(clientAuth,
                        "/v1/conversations/" + conversationId + "/messages").param("cursor", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_cursor"));
    }

    @Test
    void listMessages_byNonVisibleTenant_returns404OnEveryPage() throws Exception {
        UUID conversationId = null;
        for (int i = 0; i < 3; i++) {
            conversationId = postMessage("user-1", "msg-" + i);
        }
        UUID partnerB = createPartner();
        UUID clientB = createClient(bearerFor(partnerB), partnerB);
        String clientBAuth = bearerFor(clientB);

        // The visibility probe guards the first page and any cursor-carrying page alike.
        mockMvc.perform(getAs(clientBAuth,
                        "/v1/conversations/" + conversationId + "/messages").param("limit", "2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("conversation_not_found"));
        JsonNode ownerPage = getPage("/v1/conversations/" + conversationId + "/messages",
                clientAuth, 2, null);
        mockMvc.perform(getAs(clientBAuth, "/v1/conversations/" + conversationId + "/messages")
                        .param("limit", "2").param("cursor", ownerPage.get("next_cursor").asText()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("conversation_not_found"));
    }

    @Test
    void listChildren_walk_returnsAllChildrenInPages() throws Exception {
        // The operator has the partner from setUp plus two more: 3 children, pages of 2 + 1.
        createPartner();
        createPartner();

        List<String> walked = walk("/v1/tenants/" + operatorId + "/children", operatorAuth, 2);

        assertThat(walked).hasSize(3);
        assertThat(new HashSet<>(walked)).hasSize(3);
        assertThat(walked).isSorted();
    }

    @Test
    void listAgents_walk_returnsAllAgentsInPages() throws Exception {
        createAgent(clientAuth, clientId);
        createAgent(clientAuth, clientId); // 3 agents in total with setUp's

        List<String> walked = walk("/v1/tenants/" + clientId + "/agents", clientAuth, 2);

        assertThat(walked).hasSize(3);
        assertThat(new HashSet<>(walked)).hasSize(3);
        assertThat(walked).isSorted();
    }

    @Test
    void listAgents_forNonVisibleTenant_returnsEmptyPage() throws Exception {
        UUID partnerB = createPartner();
        UUID clientB = createClient(bearerFor(partnerB), partnerB);

        // Preserves the pre-pagination semantics: an out-of-scope parent yields an empty
        // list (RLS filters the rows), not a 404 — the parent id itself is not probed here.
        mockMvc.perform(getAs(bearerFor(clientB), "/v1/tenants/" + clientId + "/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.next_cursor").value((Object) null));
    }

    // --- helpers ---

    /** Walks the endpoint to exhaustion with the given page size; returns all ids in order. */
    private List<String> walk(String path, String auth, int limit) throws Exception {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = getPage(path, auth, limit, cursor);
            assertThat(page.get("data").size()).isLessThanOrEqualTo(limit);
            ids.addAll(idsOf(page));
            cursor = page.get("next_cursor").isNull() ? null : page.get("next_cursor").asText();
        } while (cursor != null);
        return ids;
    }

    private JsonNode getPage(String path, String auth, int limit, String cursor) throws Exception {
        MockHttpServletRequestBuilder request = getAs(auth, path).param("limit", String.valueOf(limit));
        if (cursor != null) {
            request = request.param("cursor", cursor);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private List<String> idsOf(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("data").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private UUID postMessage(String externalIdentityRef, String content) throws Exception {
        String body = mockMvc.perform(postAs(clientAuth, "/v1/agents/" + agentId + "/messages",
                        new PostMessageRequest(externalIdentityRef, content)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("conversation_id").asText());
    }

    private String bearerFor(UUID tenantId) {
        TenantContext.setCurrentTenantId(tenantId);
        try {
            return "Bearer " + apiKeyService.createApiKey(tenantId, "it-key").plaintextKey();
        } finally {
            TenantContext.clear();
        }
    }

    private MockHttpServletRequestBuilder postAs(String auth, String path, Object body) throws Exception {
        return post(path)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder getAs(String auth, String path) {
        return get(path).header(HttpHeaders.AUTHORIZATION, auth);
    }

    private UUID createPartner() throws Exception {
        String body = mockMvc.perform(postAs(operatorAuth, "/v1/tenants/partner",
                        new CreatePartnerRequest("Partner", operatorId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private UUID createClient(String partnerAuth, UUID partnerId) throws Exception {
        String body = mockMvc.perform(postAs(partnerAuth, "/v1/tenants/client",
                        new CreateClientRequest("Client", partnerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private UUID createAgent(String auth, UUID tenantId) throws Exception {
        String body = mockMvc.perform(postAs(auth, "/v1/tenants/" + tenantId + "/agents",
                        new CreateAgentRequest("Bot", "You are helpful", "anthropic",
                                "claude-sonnet-4-7", null, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }
}
