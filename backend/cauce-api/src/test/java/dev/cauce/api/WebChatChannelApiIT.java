package dev.cauce.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The web-chat channel of ADR 0014, W1, end to end: created for an agent only when its tenant has a
 * spend limit, reached anonymously by its public key from an allowed origin, closed to every other
 * origin and key alike, and holding the tenant's limit in place while it is active.
 */
class WebChatChannelApiIT extends AbstractBusinessApiIT {

    private static final String SHOP = "https://tienda.example.com";

    private static final String CREATE = """
            {"turnstile_secret": "0x4AAAAAAA-secreto", "turnstile_site_key": "0x4AAAAAAA-sitio",
             "allowed_origins": ["https://Tienda.example.com/", "http://localhost:4321"],
             "privacy_policy_url": "https://tienda.example.com/privacidad"}
            """;

    @Test
    void aWebChat_needsASpendLimit_thenHasAPublicKey_andNeverShowsOrStoresItsSecretInClear() throws Exception {
        UUID agentId = createAgent(clientId, clientAuth, List.of());

        postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", CREATE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("spend_limit_required"));
        setLimit();

        String body = postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", CREATE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel_type").value("web_chat"))
                .andExpect(jsonPath("$.public_key").value(org.hamcrest.Matchers.matchesPattern("pk_[A-Za-z0-9]{24}")))
                .andExpect(jsonPath("$.allowed_origins[0]").value(SHOP))
                .andExpect(jsonPath("$.allowed_origins[1]").value("http://localhost:4321"))
                .andExpect(jsonPath("$.consent_mode").value("NOTICE"))
                .andExpect(jsonPath("$.turnstile_secret").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("secreto");
        String id = objectMapper.readTree(body).get("id").asText();
        String stored = new JdbcTemplate(adminDataSource).queryForObject(
                "SELECT credential FROM channel_configs WHERE id = ?::uuid", String.class, id);
        assertThat(stored).doesNotContain("secreto");

        getAs(partnerAuth, "/v1/web-chat-channels/" + id).andExpect(status().isOk());
        getAs(otherClientAuth, "/v1/web-chat-channels/" + id).andExpect(status().isNotFound());
    }

    @Test
    void aMalformedOrigin_orAPrivacyPolicyOverHttp_isABadRequest() throws Exception {
        setLimit();
        UUID agentId = createAgent(clientId, clientAuth, List.of());

        postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", CREATE.replace(
                "\"https://Tienda.example.com/\"", "\"https://tienda.example.com/tienda\""))
                .andExpect(status().isBadRequest());
        postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", CREATE.replace(
                "https://tienda.example.com/privacidad", "http://tienda.example.com/privacidad"))
                .andExpect(status().isBadRequest());
        postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", CREATE.replace(
                "\"https://Tienda.example.com/\", \"http://localhost:4321\"", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theConfig_isReadAnonymously_fromAnAllowedOrigin_andRefusedAlikeToAnyOtherOriginOrKey() throws Exception {
        String publicKey = createChannel();

        mockMvc.perform(get("/web/v1/" + publicKey + "/config").header(HttpHeaders.ORIGIN, SHOP))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, SHOP))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("max-age=300")))
                .andExpect(jsonPath("$.public_key").value(publicKey))
                .andExpect(jsonPath("$.turnstile_site_key").value("0x4AAAAAAA-sitio"))
                .andExpect(jsonPath("$.privacy_policy_url").value("https://tienda.example.com/privacidad"))
                .andExpect(jsonPath("$.consent_required").value(false))
                .andExpect(jsonPath("$.turnstile_secret").doesNotExist());
        // Not a browser: no CORS to check.
        mockMvc.perform(get("/web/v1/" + publicKey + "/config")).andExpect(status().isOk());

        mockMvc.perform(get("/web/v1/" + publicKey + "/config").header(HttpHeaders.ORIGIN, "https://otra.example.com"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(get("/web/v1/pk_AAAAAAAAAAAAAAAAAAAAAAAA/config").header(HttpHeaders.ORIGIN, SHOP))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(get("/web/v1/no-es-una-clave/config").header(HttpHeaders.ORIGIN, SHOP))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPreflight_fromAnAllowedOrigin_isAnsweredWithTheSurfacesMethodsAndHeaders_andNoCredentials() throws Exception {
        String publicKey = createChannel();

        mockMvc.perform(options("/web/v1/" + publicKey + "/sessions").header(HttpHeaders.ORIGIN, SHOP)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, SHOP))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        org.hamcrest.Matchers.containsString("Authorization")))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        mockMvc.perform(options("/web/v1/" + publicKey + "/sessions").header(HttpHeaders.ORIGIN, "https://otra.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theLook_comesFromTheChannel_isReadByTheWidget_andAPairBelowAaIsRefused() throws Exception {
        setLimit();
        UUID agentId = createAgent(clientId, clientAuth, List.of());
        String brand = """
                {"name": "Cauce", "avatar_url": "https://caucewebs.es/avatar.png", "primary_color": "#0B5FFF",
                 "on_primary_color": "#FFFFFF", "background_color": "#FFFFFF", "text_color": "#111827",
                 "font_family": "Inter, system-ui, sans-serif", "greeting": "Hola, ¿en qué te ayudo?",
                 "unavailable_message": "Ahora no puedo responder.", "contact": "hola@caucewebs.es", "locale": "es"}
                """;
        String body = postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels",
                CREATE.replace("\"privacy_policy_url\"", "\"appearance\": " + brand + ", \"privacy_policy_url\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appearance.primary_color").value("#0b5fff"))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();
        String key = objectMapper.readTree(body).get("public_key").asText();

        mockMvc.perform(get("/web/v1/" + key + "/config").header(HttpHeaders.ORIGIN, SHOP))
                .andExpect(jsonPath("$.appearance.name").value("Cauce"))
                .andExpect(jsonPath("$.appearance.font_family").value("Inter, system-ui, sans-serif"))
                .andExpect(jsonPath("$.appearance.contact").value("hola@caucewebs.es"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/v1/web-chat-channels/" + id + "/appearance").header(HttpHeaders.AUTHORIZATION, clientAuth)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(brand.replace("#111827", "#9CA3AF")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/v1/web-chat-channels/" + id + "/appearance").header(HttpHeaders.AUTHORIZATION, clientAuth)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(brand.replace("\"Cauce\"", "\"Ana\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.appearance.name").value("Ana"));
        mockMvc.perform(get("/web/v1/" + key + "/config").header(HttpHeaders.ORIGIN, SHOP))
                .andExpect(jsonPath("$.appearance.name").value("Ana"));
    }

    @Test
    void theShopAssistantMode_isSetOnTheChannel_readByTheWidgetWhileOn_andItsShapeChecked() throws Exception {
        setLimit();
        String body = postJson(clientAuth, "/v1/agents/" + createAgent(clientId, clientAuth, List.of())
                + "/web-chat-channels", CREATE).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();
        String key = objectMapper.readTree(body).get("public_key").asText();
        String assistant = """
                {"enabled": true, "bubble": "¿Te ayudo a elegir plan?", "card_button_label": "Contratar",
                 "routes": [{"path": "/", "signals": ["comparing"]}, {"path": "/pagar", "signals": ["idle_cart", "exit_intent"]}],
                 "templates": {"idle_cart": "¿Te ayudo a terminar?"},
                 "variants": [{"id": "b", "bubble": "¿Hablamos?"}]}
                """;
        mockMvc.perform(get("/web/v1/" + key + "/config").header(HttpHeaders.ORIGIN, SHOP))
                .andExpect(jsonPath("$.assistant").doesNotExist());

        putAssistant(id, assistant).andExpect(status().isOk())
                .andExpect(jsonPath("$.assistant.routes[1].path").value("/pagar"));
        mockMvc.perform(get("/web/v1/" + key + "/config").header(HttpHeaders.ORIGIN, SHOP))
                .andExpect(jsonPath("$.assistant.bubble").value("¿Te ayudo a elegir plan?"))
                .andExpect(jsonPath("$.assistant.tuning.idle_cart_minutes").value(3))
                .andExpect(jsonPath("$.assistant.variants[0].id").value("b"));
        assertThat(new JdbcTemplate(adminDataSource).queryForObject("SELECT count(*) FROM audit_outbox WHERE "
                + "event_type = 'admin.web_chat.assistant_updated'", Integer.class)).isEqualTo(1);

        putAssistant(id, assistant.replace("\"comparing\"]", "\"on_load\"]")).andExpect(status().isBadRequest());
        putAssistant(id, assistant.replace("\"enabled\": true", "\"enabled\": false")).andExpect(status().isOk());
        mockMvc.perform(get("/web/v1/" + key + "/config").header(HttpHeaders.ORIGIN, SHOP))
                .andExpect(jsonPath("$.assistant").doesNotExist());
    }

    private org.springframework.test.web.servlet.ResultActions putAssistant(String id, String json) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/v1/web-chat-channels/" + id + "/assistant").header(HttpHeaders.AUTHORIZATION, clientAuth)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void aChannelWithoutALook_getsTheNeutralOne() throws Exception {
        String key = createChannel();

        mockMvc.perform(get("/web/v1/" + key + "/config").header(HttpHeaders.ORIGIN, SHOP))
                .andExpect(jsonPath("$.appearance.name").value("Asistente"))
                .andExpect(jsonPath("$.appearance.locale").value("es"));
    }

    @Test
    void whileAWebChatIsActive_theTenantsSpendLimitCannotBeRemoved() throws Exception {
        createChannel();

        mockMvc.perform(delete("/v1/tenants/" + clientId + "/spend-limit").header(HttpHeaders.AUTHORIZATION, partnerAuth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("spend_limit_required"));
        getAs(clientAuth, "/v1/tenants/" + clientId + "/spend-limit")
                .andExpect(jsonPath("$.monthly_limit_usd").value(50.0));
    }

    private void setLimit() throws Exception {
        putJson(partnerAuth, "/v1/tenants/" + clientId + "/spend-limit", """
                {"monthly_limit_usd": 50}
                """).andExpect(status().isOk());
    }

    private String createChannel() throws Exception {
        setLimit();
        UUID agentId = createAgent(clientId, clientAuth, List.of());
        return json(postJson(clientAuth, "/v1/agents/" + agentId + "/web-chat-channels", CREATE)
                .andExpect(status().isCreated())).get("public_key").asText();
    }
}
