package dev.cauce.api;

import static dev.cauce.api.support.FakeLlmProvider.finalReply;
import static dev.cauce.api.support.FakeLlmProvider.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The business profile, the catalog and the unanswered questions over the API (ADR 0007, B4),
 * and the agent reading them through its tools: the endpoints' hierarchy rules, and a scripted
 * model whose catalog lookup returns NOT_DECLARED for an allergen the business did not state.
 */
class BusinessKnowledgeApiIT extends AbstractBusinessApiIT {

    private static final String PROFILE = """
            {"name": "Bar La Esquina", "time_zone": "Europe/Madrid",
             "facts": {"horario": "mar-dom 12:00-16:00", "terraza": "sí, 6 mesas"}}""";
    private static final String CATALOG = """
            {"declared_attributes": ["allergens"],
             "items": [
               {"ref": "bravas", "section": "Tapas", "name": "Patatas bravas", "price": 5.50,
                "attributes": {"allergens": ["huevo"], "vegetariano": true}},
               {"ref": "tarta-queso", "section": "Postres", "name": "Tarta de queso", "price": 5.00}]}""";

    @Test
    void profile_isLoadedByTheBusiness_readUpTheHierarchy_andHiddenAcrossIt() throws Exception {
        putJson(clientAuth, "/v1/tenants/" + clientId + "/business-profile", PROFILE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant_id").value(clientId.toString()))
                .andExpect(jsonPath("$.locale").value("es"));

        getAs(partnerAuth, "/v1/tenants/" + clientId + "/business-profile")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.time_zone").value("Europe/Madrid"))
                .andExpect(jsonPath("$.facts.horario").value("mar-dom 12:00-16:00"));
        getAs(otherClientAuth, "/v1/tenants/" + clientId + "/business-profile")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("business_profile_not_found"));
        putJson(otherClientAuth, "/v1/tenants/" + clientId + "/business-profile", PROFILE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("tenant_not_found"));
        putJson(partnerAuth, "/v1/tenants/" + partnerId + "/business-profile", PROFILE)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_tenant_tier"));
    }

    @Test
    void profile_withAnUnknownZone_andCatalog_withARepeatedRef_areBadRequests() throws Exception {
        putJson(clientAuth, "/v1/tenants/" + clientId + "/business-profile",
                "{\"name\": \"Bar\", \"time_zone\": \"Mars/Olympus\", \"facts\": {}}")
                .andExpect(status().isBadRequest());
        putJson(clientAuth, "/v1/tenants/" + clientId + "/catalog",
                "{\"items\": [{\"ref\": \"a\", \"section\": \"S\", \"name\": \"A\"},"
                        + "{\"ref\": \"a\", \"section\": \"S\", \"name\": \"B\"}]}")
                .andExpect(status().isBadRequest());
        getAs(clientAuth, "/v1/tenants/" + clientId + "/catalog")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("catalog_not_found"));
    }

    @Test
    void agent_readsTheCatalogThroughItsTool_andRecordsWhatItCannotAnswer() throws Exception {
        putJson(clientAuth, "/v1/tenants/" + clientId + "/catalog", CATALOG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].price").value(5.5))
                .andExpect(jsonPath("$.items[1].attributes").isEmpty());
        UUID agentId = createAgent(clientId, clientAuth, List.of("search_catalog", "record_unanswered_question"));
        llm.script(
                toolCall("search_catalog", Map.of("query", "tarta de queso")),
                toolCall("record_unanswered_question", Map.of("question", "¿Alérgenos de la tarta de queso?")),
                finalReply("De la tarta de queso no consta la información de alérgenos."));

        UUID conversationId = sendAndProcess(agentId, "cliente-1", "¿Qué alérgenos tiene la tarta de queso?");

        assertThat(toolOutputs(conversationId).get(0)).contains("\"allergens\":\"NOT_DECLARED\"")
                .contains("Tarta de queso").doesNotContain("bravas");
        // The model was offered exactly the agent's grant.
        assertThat(llm.invocations().get(0).tools()).extracting(tool -> tool.name())
                .containsExactly("search_catalog", "record_unanswered_question");

        getAs(partnerAuth, "/v1/tenants/" + clientId + "/unanswered-questions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].question").value("¿Alérgenos de la tarta de queso?"))
                .andExpect(jsonPath("$.data[0].agent_id").value(agentId.toString()))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
        getAs(otherClientAuth, "/v1/tenants/" + clientId + "/unanswered-questions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        // C5: the lookups are in the audit chain, by hash.
        assertThat(new JdbcTemplate(adminDataSource).queryForObject("SELECT count(*) FROM audit_outbox WHERE "
                + "event_type = 'conduct.tool.called' AND tenant_id = ?", Long.class, clientId)).isEqualTo(2);
    }
}
